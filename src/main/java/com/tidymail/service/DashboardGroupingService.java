package com.tidymail.service;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Account-serialized corrections with replayable history and explicit
 * separation precedence.
 */
@Service
public class DashboardGroupingService {
    private final JdbcTemplate jdbc;

    public DashboardGroupingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Correction(String operation, List<UUID> units, String label, UUID resetId, long revision) {
    }

    public record Review(long revision, List<Unit> affected, List<Map<String, Object>> history, String explanation) {
    }

    public record Unit(@com.fasterxml.jackson.annotation.JsonIgnore long id, UUID publicId, String kind,
            String automaticKey, String label) {
    }

    private record Event(UUID id, String kind, List<Long> units, String label) {
    }

    private record Pair(long low, long high) {
        static Pair of(long a, long b) {
            return new Pair(Math.min(a, b), Math.max(a, b));
        }
    }

    private record Layout(Map<Long, String> membership, Map<String, String> labels, Set<Pair> pairs) {
    }

    @Transactional
    public Review correct(long account, Correction request, boolean apply) {
        if (request == null
                || !Set.of("MERGE", "SEPARATE", "RESET").contains(Objects.toString(request.operation(), "")))
            throw bad("Invalid correction");
        if (request.label() != null && request.label().length() > 160)
            throw bad("Group label is too long");
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE", Long.class, account);
        jdbc.update("INSERT INTO dashboard_account_settings(account_id) VALUES(?) ON CONFLICT DO NOTHING", account);
        long revision = jdbc.queryForObject(
                "SELECT grouping_revision FROM dashboard_account_settings WHERE account_id=? FOR UPDATE", Long.class,
                account);
        if (request.revision() != revision)
            throw conflict("Grouping changed. Reload and preview again.");
        List<Unit> units = jdbc.query(
                "SELECT id,public_id,kind,automatic_group_key,automatic_label FROM sender_units WHERE account_id=? ORDER BY id",
                (r, n) -> new Unit(r.getLong(1), r.getObject(2, UUID.class), r.getString(3), r.getString(4),
                        r.getString(5)),
                account);
        Map<UUID, Unit> byPublic = new HashMap<>();
        units.forEach(u -> byPublic.put(u.publicId(), u));
        var events = new ArrayList<>(jdbc.query(
                "SELECT public_id,kind,unit_ids,label FROM sender_group_corrections WHERE account_id=? AND active ORDER BY id",
                (r, n) -> new Event(r.getObject(1, UUID.class), r.getString(2),
                        Arrays.asList((Long[]) r.getArray(3).getArray()), r.getString(4)),
                account));
        Layout before = layout(units, events);
        List<Long> affected;
        Event added = null;
        if (request.operation().equals("RESET")) {
            Event reset = events.stream().filter(e -> e.id().equals(request.resetId())).findFirst()
                    .orElseThrow(() -> bad("Correction is not active for this account"));
            events.remove(reset);
            affected = reset.units();
        } else {
            if (request.units() == null || request.units().isEmpty()
                    || request.units().stream().anyMatch(u -> !byPublic.containsKey(u)))
                throw bad("Select owned sender units");
            var selected = request.units().stream().distinct().map(u -> byPublic.get(u).id()).toList();
            if (request.units().stream().anyMatch(id -> byPublic.get(id).kind().equals("MESSAGE")))
                throw bad("Fresh sender metadata is required before correcting message-level fallback groups");
            if (request.operation().equals("SEPARATE") && selected.size() != 1)
                throw bad("Choose one atomic address/list unit to separate");
            Set<String> keys = new HashSet<>();
            selected.forEach(id -> keys.add(before.membership().get(id)));
            affected = request.operation().equals("MERGE")
                    ? units.stream().filter(u -> keys.contains(before.membership().get(u.id()))).map(Unit::id).toList()
                    : selected;
            if (request.operation().equals("MERGE") && keys.size() < 2)
                throw bad("Choose units from at least two groups");
            added = new Event(UUID.randomUUID(), request.operation(), affected, request.label());
            events.add(added);
        }
        Layout after = layout(units, events); // also rejects transitive separation conflicts
        List<Unit> affectedUnits = units.stream()
                .filter(u -> !Objects.equals(before.membership().get(u.id()), after.membership().get(u.id()))
                        || affected.contains(u.id()))
                .toList();
        if (apply) {
            if (request.operation().equals("RESET"))
                jdbc.update(
                        "UPDATE sender_group_corrections SET active=false,reset_at=now() WHERE account_id=? AND public_id=?",
                        account, request.resetId());
            else {
                Event event = added;
                jdbc.update(connection -> {
                    var ps = connection.prepareStatement(
                            "INSERT INTO sender_group_corrections(account_id,public_id,kind,unit_ids,label) VALUES(?,?,?,?,?)");
                    ps.setLong(1, account);
                    ps.setObject(2, event.id());
                    ps.setString(3, event.kind());
                    ps.setArray(4, connection.createArrayOf("bigint", event.units().toArray()));
                    ps.setString(5, event.label());
                    return ps;
                });
            }
            for (var entry : after.labels().entrySet())
                jdbc.update(
                        "INSERT INTO sender_groups(account_id,group_key,label) VALUES(?,?,?) ON CONFLICT(account_id,group_key) DO UPDATE SET label=EXCLUDED.label",
                        account, entry.getKey(), entry.getValue());
            jdbc.batchUpdate(
                    "UPDATE sender_group_members SET group_id=(SELECT id FROM sender_groups WHERE account_id=? AND group_key=?) WHERE account_id=? AND unit_id=?",
                    units, 500, (ps, u) -> {
                        ps.setLong(1, account);
                        ps.setString(2, after.membership().get(u.id()));
                        ps.setLong(3, account);
                        ps.setLong(4, u.id());
                    });
            jdbc.update("DELETE FROM sender_separations WHERE account_id=?", account);
            jdbc.batchUpdate("INSERT INTO sender_separations(account_id,low_unit_id,high_unit_id) VALUES(?,?,?)",
                    after.pairs(), 500, (ps, p) -> {
                        ps.setLong(1, account);
                        ps.setLong(2, p.low());
                        ps.setLong(3, p.high());
                    });
            jdbc.update(
                    "UPDATE dashboard_account_settings SET grouping_revision=grouping_revision+1 WHERE account_id=?",
                    account);
        }
        var history = jdbc.queryForList(
                "SELECT public_id,kind,label,created_at FROM sender_group_corrections WHERE account_id=? AND active ORDER BY id DESC",
                account);
        return new Review(apply ? revision + 1 : revision, affectedUnits, history,
                "Grouping only. Draft selection clears; confirmed message targets stay fixed.");
    }

    /**
     * Replays saved corrections over a new automatic baseline; no provider action.
     */
    @Transactional
    public void rebuild(long account) {
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE", Long.class, account);
        var units = jdbc.query(
                "SELECT id,public_id,kind,automatic_group_key,automatic_label FROM sender_units WHERE account_id=? ORDER BY id",
                (r, n) -> new Unit(r.getLong(1), r.getObject(2, UUID.class), r.getString(3), r.getString(4),
                        r.getString(5)),
                account);
        var events = jdbc.query(
                "SELECT public_id,kind,unit_ids,label FROM sender_group_corrections WHERE account_id=? AND active ORDER BY id",
                (r, n) -> new Event(r.getObject(1, UUID.class), r.getString(2),
                        Arrays.asList((Long[]) r.getArray(3).getArray()), r.getString(4)),
                account);
        var after = layout(units, events);
        for (var entry : after.labels().entrySet())
            jdbc.update(
                    "INSERT INTO sender_groups(account_id,group_key,label) VALUES(?,?,?) ON CONFLICT(account_id,group_key) DO UPDATE SET label=EXCLUDED.label",
                    account, entry.getKey(), entry.getValue());
        jdbc.batchUpdate(
                "UPDATE sender_group_members SET group_id=(SELECT id FROM sender_groups WHERE account_id=? AND group_key=?) WHERE account_id=? AND unit_id=?",
                units, 500,
                (ps, u) -> {
                    ps.setLong(1, account);
                    ps.setString(2, after.membership().get(u.id()));
                    ps.setLong(3, account);
                    ps.setLong(4, u.id());
                });
        jdbc.update("DELETE FROM sender_separations WHERE account_id=?", account);
        jdbc.batchUpdate("INSERT INTO sender_separations(account_id,low_unit_id,high_unit_id) VALUES(?,?,?)",
                after.pairs(), 500,
                (ps, p) -> {
                    ps.setLong(1, account);
                    ps.setLong(2, p.low());
                    ps.setLong(3, p.high());
                });
        jdbc.update(
                "INSERT INTO dashboard_account_settings(account_id,grouping_revision) VALUES(?,1) ON CONFLICT(account_id) DO UPDATE SET grouping_revision=dashboard_account_settings.grouping_revision+1",
                account);
    }

    private static Layout layout(List<Unit> units, List<Event> events) {
        Map<Long, String> member = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        Set<Pair> pairs = new HashSet<>();
        units.forEach(u -> {
            member.put(u.id(), u.automaticKey());
            labels.put(u.automaticKey(), u.label());
        });
        for (Event event : events) {
            if (event.kind().equals("SEPARATE")) {
                long unit = event.units().getFirst();
                String current = member.get(unit);
                for (var entry : member.entrySet())
                    if (entry.getKey() != unit && Objects.equals(current, entry.getValue()))
                        pairs.add(Pair.of(unit, entry.getKey()));
                String key = "separate:" + event.id();
                member.put(unit, key);
                labels.put(key, units.stream().filter(u -> u.id() == unit).findFirst().orElseThrow().label());
            } else {
                Set<String> keys = new HashSet<>();
                event.units().forEach(id -> keys.add(member.get(id)));
                Set<Long> merging = new HashSet<>();
                member.forEach((id, key) -> {
                    if (keys.contains(key))
                        merging.add(id);
                });
                if (pairs.stream().anyMatch(p -> merging.contains(p.low()) && merging.contains(p.high())))
                    throw conflict("These groups contain units kept separate. Reset that separation before merging.");
                String key = "manual:" + event.id();
                merging.forEach(id -> member.put(id, key));
                labels.put(key,
                        event.label() == null || event.label().isBlank() ? "Merged senders" : event.label().strip());
            }
        }
        return new Layout(member, labels, pairs);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
