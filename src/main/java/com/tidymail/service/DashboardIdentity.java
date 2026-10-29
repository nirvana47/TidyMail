package com.tidymail.service;

import java.net.IDN;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative, versioned grouping evidence; an uncertain parse never merges identities. */
public record DashboardIdentity(String address, String kind, String key, String reason) {
    private static final Pattern LOCAL = Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*");

    public static DashboardIdentity parse(List<String> from, List<String> lists, String messageId) {
        String address = from.size() == 1 ? mailbox(from.getFirst()) : null;
        if (address == null) return new DashboardIdentity(null,"MESSAGE",messageId,"Missing or ambiguous From; kept separate");
        if (!lists.isEmpty()) {
            if (lists.size() == 1) {
                String raw = lists.getFirst();
                if (raw != null && raw.matches("[^<>\\r\\n]*<[^<>\\s]+>\\s*")) {
                    String list = domain(raw.substring(raw.indexOf('<')+1,raw.indexOf('>')));
                    if (list != null) return new DashboardIdentity(address,"LIST",list,"Single valid List-ID");
                }
            }
            return new DashboardIdentity(address,"MESSAGE",messageId,"Ambiguous List-ID; kept separate");
        }
        return new DashboardIdentity(address,"ADDRESS",address,"Kept separate — insufficient domain evidence");
    }

    public static String mailbox(String raw) {
        if (raw == null || raw.length()>1024 || raw.indexOf('\r')>=0 || raw.indexOf('\n')>=0) return null;
        String value=raw.trim();
        if (value.contains("<")) {
            if (!value.matches("(?:\"[^\"\\r\\n]*\"|[^<>,\"]*)\\s*<[^<>]+>\\s*")) return null;
            value=value.substring(value.indexOf('<')+1,value.indexOf('>')).trim();
        }
        int at=value.indexOf('@');
        if (at<1 || at!=value.lastIndexOf('@') || !LOCAL.matcher(value.substring(0,at)).matches()) return null;
        String domain=domain(value.substring(at+1));
        return domain==null ? null : value.substring(0,at)+"@"+domain;
    }

    private static String domain(String raw) {
        try {
            String value=IDN.toASCII(raw,IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            return value.length()<=253 && value.contains(".") && !value.startsWith(".") && !value.endsWith(".") ? value : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }
}
