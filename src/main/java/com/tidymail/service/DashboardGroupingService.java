package com.tidymail.service;

import com.tidymail.repository.DashboardRepository;
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

    private final DashboardRepository dashboard;

    public DashboardGroupingService(JdbcTemplate jdbc, DashboardRepository dashboard) {
        this.jdbc = jdbc;
        this.dashboard = dashboard;
    }

    public record Correction(String operation, List<UUID> units, String label, UUID resetId, long revision,
            String remainderLabel, UUID group, List<String> addresses) {
        public Correction(String operation, List<UUID> units, String label, UUID resetId, long revision) {
            this(operation, units, label, resetId, revision, null, null, null);
        }
    }

    public record Review(long revision, List<Unit> affected, List<Map<String, Object>> history, String explanation) {
    }

    public record Unit(@com.fasterxml.jackson.annotation.JsonIgnore long id, UUID publicId, String kind,
            String automaticKey, String label, @com.fasterxml.jackson.annotation.JsonIgnore Long source,
            @com.fasterxml.jackson.annotation.JsonIgnore String address) {
    }

    private record Event(UUID id, String kind, List<Long> units, String label, Integer splitCount, String remainderLabel) {
    }

    private record Pair(long low, long high) {
        static Pair of(long a, long b) {
            return new Pair(Math.min(a, b), Math.max(a, b));
        }
    }

    private record Layout(Map<Long, String> membership, Map<String, String> labels, Set<Pair> pairs, Set<Long> detached) {
    }

    @Transactional
    public Review correct(long account, Correction request, boolean apply) {
        if (request == null
                || !Set.of("MERGE", "SEPARATE", "SPLIT", "RESET").contains(Objects.toString(request.operation(), "")))
            throw bad("Invalid correction");
        boolean split = request.operation().equals("SPLIT");
        if ((request.label() != null && request.label().length() > 160)
                || (split && (request.label() == null || request.label().isBlank()
                    || request.remainderLabel() == null || request.remainderLabel().isBlank()
                    || request.remainderLabel().length() > 160)))
            throw bad("Enter group names of at most 160 characters");
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE", Long.class, account);
        jdbc.update("INSERT INTO dashboard_account_settings(account_id) VALUES(?) ON CONFLICT DO NOTHING", account);
        long revision = jdbc.queryForObject(
                "SELECT grouping_revision FROM dashboard_account_settings WHERE account_id=? FOR UPDATE", Long.class,
                account);
        if (request.revision() != revision)
            throw conflict("Grouping changed. Reload and preview again.");
        List<Unit> units = new ArrayList<>(units(account));
        Map<UUID, Unit> byPublic = new HashMap<>();
        units.forEach(u -> byPublic.put(u.publicId(), u));
        var events = new ArrayList<>(events(account));
        Layout before = layout(units, events);
        List<Long> affected;
        Event added = null;
        if (request.operation().equals("RESET")) {
            Event reset = events.stream().filter(e -> e.id().equals(request.resetId())).findFirst()
                    .orElseThrow(() -> bad("Correction is not active for this account"));
            events.remove(reset);
            affected = reset.units();
        } else if (split) {
            var partition = partition(account, request, units, byPublic, apply);
            affected = partition.units();
            added = new Event(UUID.randomUUID(), "SPLIT", affected, request.label(),
                    partition.firstCount(), request.remainderLabel());
            events.add(added);
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
                    ? units.stream().filter(u -> keys.contains(before.membership().get(u.id())))
                        .filter(u -> u.source() == null || before.detached().contains(u.id())).map(Unit::id).toList()
                    : selected;
            if (request.operation().equals("MERGE") && keys.size() < 2)
                throw bad("Choose units from at least two groups");
            added = new Event(UUID.randomUUID(), request.operation(), affected, request.label(), null, null);
            events.add(added);
        }
        Layout after = layout(units, events); // also rejects transitive separation conflicts
        Set<Long> affectedIds = new HashSet<>(affected);
        List<Unit> affectedUnits = units.stream()
                .filter(u -> !Objects.equals(before.membership().get(u.id()), after.membership().get(u.id()))
                        || affectedIds.contains(u.id()))
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
                            "INSERT INTO sender_group_corrections(account_id,public_id,kind,unit_ids,label,split_count,remainder_label) VALUES(?,?,?,?,?,?,?)");
                    ps.setLong(1, account);
                    ps.setObject(2, event.id());
                    ps.setString(3, event.kind());
                    ps.setArray(4, connection.createArrayOf("bigint", event.units().toArray()));
                    ps.setString(5, event.label());
                    ps.setObject(6, event.splitCount(), java.sql.Types.INTEGER);
                    ps.setString(7, event.remainderLabel());
                    return ps;
                });
            }
            saveLayout(account, units, after);
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

    private record Partition(List<Long> units, int firstCount) {}

    private Partition partition(long account, Correction request, List<Unit> units, Map<UUID, Unit> byPublic,
            boolean apply) {
        if (request.group() == null || request.addresses() == null || request.addresses().isEmpty()
                || request.addresses().stream().anyMatch(Objects::isNull))
            throw bad("Choose addresses in one sender group");
        var members = dashboard.grouping(account, request.group()).units().stream()
            .filter(u -> !u.kind().equals("MESSAGE")).toList();
        Set<String> selected = Set.copyOf(request.addresses());
        Set<String> known = new HashSet<>();
        members.forEach(u -> known.addAll(u.addresses()));
        if (!known.containsAll(selected))
            throw bad("Select addresses that belong to this sender group");
        if (known.size() == selected.size())
            throw bad("Leave at least one address in the other group");

        Map<Long, Map<String, Unit>> existing = new HashMap<>();
        for (Unit unit : units) {
            if (unit.source() != null)
                existing.computeIfAbsent(unit.source(), unused -> new HashMap<>()).put(unit.address(), unit);
        }
        List<Long> first = new ArrayList<>(), rest = new ArrayList<>();
        for (var member : members) {
            Unit unit = byPublic.get(member.id());
            if (unit == null) throw conflict("Grouping changed. Reload and preview again.");
            for (String address : member.addresses()) {
                Unit destination = unit;
                if (unit.kind().equals("LIST")) {
                    var children = existing.computeIfAbsent(unit.id(), unused -> new HashMap<>());
                    destination = children.get(address);
                    if (destination == null) {
                        destination = addressUnit(account, unit, address, apply ? null : -(long) units.size() - 1);
                        children.put(address, destination);
                        units.add(destination);
                    }
                }
                (selected.contains(address) ? first : rest).add(destination.id());
            }
        }
        int firstCount = first.size();
        first.addAll(rest);
        return new Partition(first, firstCount);
    }

    private Unit addressUnit(long account, Unit source, String address, Long previewId) {
        UUID publicId = UUID.randomUUID();
        long id = previewId != null ? previewId : jdbc.queryForObject("""
            INSERT INTO sender_units(account_id,public_id,kind,canonical_key,automatic_group_key,automatic_label,evidence_reason)
            VALUES(?,?,'SPLIT_ADDRESS',?,?,?,'Address split within a mailing-list identity') RETURNING id
            """, Long.class, account, publicId, source.id() + ":" + address, source.automaticKey(), source.label());
        if (previewId == null)
            jdbc.update("INSERT INTO sender_address_units(account_id,source_unit_id,address,unit_id) VALUES(?,?,?,?)",
                account, source.id(), address, id);
        return new Unit(id, publicId, "SPLIT_ADDRESS", source.automaticKey(), source.label(), source.id(), address);
    }

    private List<Unit> units(long account) {
        return jdbc.query("""
            SELECT u.id,u.public_id,u.kind,COALESCE(source.automatic_group_key,u.automatic_group_key),
                COALESCE(source.automatic_label,u.automatic_label),split.source_unit_id,split.address
            FROM sender_units u LEFT JOIN sender_address_units split ON split.account_id=u.account_id AND split.unit_id=u.id
            LEFT JOIN sender_units source ON source.account_id=split.account_id AND source.id=split.source_unit_id
            WHERE u.account_id=? ORDER BY u.id
            """, (r,n) -> new Unit(r.getLong(1),r.getObject(2,UUID.class),r.getString(3),
                r.getString(4),r.getString(5),r.getObject(6,Long.class),r.getString(7)), account);
    }

    private List<Event> events(long account) {
        return jdbc.query("""
            SELECT public_id,kind,unit_ids,label,split_count,remainder_label
            FROM sender_group_corrections WHERE account_id=? AND active ORDER BY id
            """, (r,n) -> new Event(r.getObject(1,UUID.class),r.getString(2),
                Arrays.asList((Long[])r.getArray(3).getArray()),r.getString(4),r.getObject(5,Integer.class),r.getString(6)),
            account);
    }

    /** Replays saved corrections over the current automatic groups. */
    @Transactional
    public void rebuild(long account) {
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE", Long.class, account);
        var units = units(account);
        saveLayout(account, units, layout(units, events(account)));
        jdbc.update(
            "INSERT INTO dashboard_account_settings(account_id,grouping_revision) VALUES(?,1) ON CONFLICT(account_id) DO UPDATE SET grouping_revision=dashboard_account_settings.grouping_revision+1",
            account);
    }

    private void saveLayout(long account, List<Unit> units, Layout after) {
        jdbc.batchUpdate(
                "INSERT INTO sender_groups(account_id,group_key,label) VALUES(?,?,?) ON CONFLICT(account_id,group_key) DO UPDATE SET label=EXCLUDED.label WHERE sender_groups.label IS DISTINCT FROM EXCLUDED.label",
                after.labels().entrySet(),500,(ps,entry)->{ps.setLong(1,account);ps.setString(2,entry.getKey());ps.setString(3,entry.getValue());});
        jdbc.batchUpdate(
                """
                INSERT INTO sender_group_members(account_id,unit_id,group_id)
                SELECT account_id,?,id FROM sender_groups WHERE account_id=? AND group_key=?
                ON CONFLICT(account_id,unit_id) DO UPDATE SET group_id=EXCLUDED.group_id
                WHERE sender_group_members.group_id IS DISTINCT FROM EXCLUDED.group_id
                """, units, 500, (ps,u) -> {
                    ps.setLong(1,u.id());
                    ps.setLong(2,account);
                    ps.setString(3,after.membership().get(u.id()));
                });
        jdbc.batchUpdate("""
            UPDATE sender_address_units SET active=? WHERE account_id=? AND unit_id=? AND active IS DISTINCT FROM ?
            """, units.stream().filter(u -> u.source() != null).toList(), 500, (ps,u) -> {
                boolean active = after.detached().contains(u.id());
                ps.setBoolean(1,active);
                ps.setLong(2,account);
                ps.setLong(3,u.id());
                ps.setBoolean(4,active);
            });
        jdbc.update("DELETE FROM sender_separations WHERE account_id=?", account);
        jdbc.batchUpdate("INSERT INTO sender_separations(account_id,low_unit_id,high_unit_id) VALUES(?,?,?)",
                after.pairs(), 500,
                (ps, p) -> {
                    ps.setLong(1, account);
                    ps.setLong(2, p.low());
                    ps.setLong(3, p.high());
                });
    }

    private static Layout layout(List<Unit> units, List<Event> events) {
        Map<Long, String> member = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        Set<Pair> pairs = new HashSet<>();
        Set<Long> detached = new HashSet<>();
        Map<Long, List<Long>> children = new HashMap<>();
        Map<Long, Unit> byId = new HashMap<>();
        units.forEach(u -> {
            byId.put(u.id(), u);
            if (u.source() != null)
                children.computeIfAbsent(u.source(), unused -> new ArrayList<>()).add(u.id());
            member.put(u.id(), u.automaticKey());
            labels.put(u.automaticKey(), u.label());
        });
        for (Event event : events) {
            if (event.kind().equals("SEPARATE")) {
                long unit = event.units().getFirst();
                String current = member.get(unit);
                for (var entry : member.entrySet())
                    if (entry.getKey() != unit && Objects.equals(current, entry.getValue())
                            && !children.getOrDefault(unit,List.of()).contains(entry.getKey()))
                        pairs.add(Pair.of(unit, entry.getKey()));
                String key = "separate:" + event.id();
                member.put(unit, key);
                for (long child : children.getOrDefault(unit,List.of()))
                    if (!detached.contains(child)) member.put(child,key);
                labels.put(key, units.stream().filter(u -> u.id() == unit).findFirst().orElseThrow().label());
            } else if (event.kind().equals("SPLIT")) {
                // The metadata trigger already preserves memberships with this prefix.
                String first = "separate:" + event.id() + ":first";
                String rest = "separate:" + event.id() + ":rest";
                for (int i = 0; i < event.units().size(); i++) {
                    long unit = event.units().get(i);
                    if (!member.containsKey(unit)) continue;
                    String key = i < event.splitCount() ? first : rest;
                    member.put(unit, key);
                    if (byId.get(unit).source() != null) detached.add(unit);
                    for (long child : children.getOrDefault(unit,List.of()))
                        if (!detached.contains(child)) member.put(child,key);
                }
                labels.put(first, event.label().strip());
                labels.put(rest, event.remainderLabel().strip());
            } else {
                Set<String> keys = new HashSet<>();
                for (long id : event.units()) {
                    Unit unit = byId.get(id);
                    if (unit == null) continue;
                    // Resetting the earlier split must not widen a later merge to the whole list.
                    if (unit.source() != null && detached.add(id)) member.put(id, "address-unit:" + id);
                    keys.add(member.get(id));
                }
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
        return new Layout(member, labels, pairs, detached);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
