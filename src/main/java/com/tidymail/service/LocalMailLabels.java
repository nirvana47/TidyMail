package com.tidymail.service;

import java.util.*;
import java.util.regex.Pattern;

/** Descriptive mail labels from stored headers. These do not qualify cleanup recommendations. */
public final class LocalMailLabels {
    public static final String VERSION = "gmail-header-rules-v3";
    public static final Set<String> EXTRA_FILTERS = Set.of("BANKING", "INVESTING", "SHOPPING", "DOCUMENT_COMMENT",
            "OLDER_DOCUMENT_COMMENT", "PAST_EVENT", "GMAIL_PROMOTIONS");

    private static final List<String> BANKS = List.of("chase.com", "bankofamerica.com", "wellsfargo.com");
    private static final List<String> INVESTORS = List.of("fidelity.com", "vanguard.com", "merrill.com", "ml.com",
            "robinhood.com", "coinbase.com", "investvoyager.com");
    private static final Map<String,String> STORES = Map.ofEntries(
            Map.entry("amazon.com","Amazon"), Map.entry("amazon.co.uk","Amazon"), Map.entry("walmart.com","Walmart"),
            Map.entry("target.com","Target"), Map.entry("ebay.com","eBay"), Map.entry("temu.com","Temu"),
            Map.entry("costco.com","Costco"), Map.entry("raleys.com","Raley's"), Map.entry("tomthumb.com","Tom Thumb"),
            Map.entry("lidl.co.uk","Lidl"), Map.entry("tesco.com","Tesco"), Map.entry("flipkart.com","Flipkart"));
    private static final List<String> SOCIAL = List.of("reddit.com", "redditmail.com", "instagram.com", "tiktok.com",
            "linkedin.com", "facebook.com", "facebookmail.com", "quora.com", "nextdoor.com");

    private static final Pattern BILL = words("bill[ ._-]*pay|invoice|billing|payment due|amount due|past due|pay your bill");
    private static final Pattern BILL_NAME = words("bill(?=\\s*[-–—:])");
    private static final Pattern RECEIPT = words("receipts?|e[ -]?receipts?");
    private static final Pattern PAID = words("paid invoice|invoice (?:was |has been )?paid|payment (?:has (?:been )?)?(?:received|posted|completed|successful)|"
            + "payment confirmation|thank you for (?:your )?payment|you(?:'ve| have) paid|refund (?:issued|posted|processed)");
    private static final Pattern NOT_PAID = words("(?:no|not) (?:paid|received|posted|completed|successful)|"
            + "payment (?:is )?(?:scheduled|pending|failed|declined|due)|unpaid");
    private static final Pattern RECEIPT_OTHER = words("receipt of (?:your )?(?:application|request|message|submission)|"
            + "acknowledg(?:e|ing) receipt|read receipt|proof of receipt|receipt requested");
    private static final Pattern STATEMENT = words("(?:current|bank|account|credit card|monthly|quarterly|billing|insurance|toll|annual) statement|"
            + "(?:your|new) statement|statement (?:is |now )*(?:available|ready)|statement of account");
    private static final Pattern NOT_STATEMENT = words("personal statement|statement of purpose|press statement|"
            + "statement of work|(?:write|draft|review|revise) (?:your |the )?(?:current )?statement");
    private static final Pattern SHIPPED = words("your package|(?:your )?order.{0,100}\\b(?:shipped|dispatched|out for delivery|delivered)|"
            + "shipment confirmation|shipping update|package (?:has arrived|is on its way)|delivery confirmation");
    private static final Pattern NOT_SHIPPED = words("your package (?:includes|contains)|(?:not|never) (?:been )?(?:shipped|delivered)|"
            + "(?:before|when|once) (?:your order|it) ships");
    private static final Pattern ORDER = words("your order|order confirmation|order (?:placed|confirmed)|we received your order|thanks for (?:your )?order");
    private static final Pattern NOT_ORDER = words("your order (?:of operations|preferences|guide)|order (?:form|of service)");
    private static final Pattern SECURITY = words("new device|(?:new|unrecognized|unusual|suspicious) (?:sign[ -]?in|log[ -]?in|account activity)|"
            + "(?:reset|change|recover) (?:your |the )?(?:account|password|username|user id|email address)|"
            + "(?:account|password|username|user id|email address) (?:reset|recovery|changed)|"
            + "security alert|verification code|two[ -]factor authentication|one[ -]time (?:password|code)");
    private static final Pattern NOT_SECURITY = words("(?:buy|shop|trade in|set up|upgrade to|choose|meet|activate) (?:a |your )?new device|"
            + "new device (?:offer|sale|deal|setup|guide|(?:has |is )?(?:shipped|arrived|out for delivery))|how to (?:reset|change|recover)");
    private static final Pattern BANK = words("bank|banking|bankofamerica");
    private static final Pattern BANK_SUBJECT = words("bank statement|your bank account|bank account alert");
    private static final Pattern NOT_BANK = words("food bank|blood bank|power bank|river bank|seed bank|bank holiday");
    private static final Pattern SOCIAL_NAME = Pattern.compile(
            "^(?:team )?(reddit|instagram|tiktok|linkedin|facebook|quora|nextdoor)(?: (?:team|security|notifications|support))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT = words("new comments?|commented on|mentioned you|replied to (?:a |your )?comment|"
            + "assigned you|resolved (?:a |your )?comment");
    private static final Pattern OFFICE_MENTION = Pattern.compile(
            "(?i)\\bmentioned you in [\"“][^\"”]+[\"”]\\.?$");
    private static final Pattern DOC_NAME = words("google (?:docs|sheets|slides)");
    private static final Pattern CALENDAR_SUBJECT = Pattern.compile(
            "^(invitation:|updated invitation:|canceled event:|cancelled event:)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALENDAR_SCHEDULE = Pattern.compile(
            "\\s+@\\s+(?:(?:mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)[,\\s]"
                    + "|\\d{4}-\\d{2}-\\d{2}\\b|(?:daily|weekly|monthly|annually|yearly"
                    + "|every \\d+ (?:days?|weeks?|months?|years?)) from\\s+).+\\([^()]+\\)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOTIFY = words("payment (?:is )?(?:scheduled|pending|failed|declined)|"
            + "new (?:voicemail|text message)|refund requested");
    private static final Set<String> PURPOSES = Set.of("BILL", "RECEIPT", "STATEMENT", "SECURITY",
            "SHIPPING", "ORDER", "DOCUMENT_COMMENT", "NOTIFICATION");

    private LocalMailLabels() {}

    static Map<String,String> classify(String address, String name, String subject, String correction,
            boolean outlook, boolean outlookMeeting) {
        String sender = Objects.toString(address, "").strip().toLowerCase(Locale.ROOT);
        String display = Objects.toString(name, "").strip();
        String title = Objects.toString(subject, "").strip();
        String domain = sender.substring(sender.lastIndexOf('@') + 1);
        String mailbox = sender.contains("@") ? sender.substring(0, sender.lastIndexOf('@')) : "";
        var labels = new TreeMap<String,String>();

        String paid = match(PAID, title, "the subject");
        String statement = unless(NOT_STATEMENT, STATEMENT, title);
        if (statement != null) labels.put("STATEMENT", statement);
        if (paid != null && !NOT_PAID.matcher(title).find()) {
            labels.put("RECEIPT", paid);
        } else if (!NOT_PAID.matcher(title).find() && !RECEIPT_OTHER.matcher(title).find()) {
            add(labels, "RECEIPT", first(match(RECEIPT, title, "the subject"),
                    match(RECEIPT, sender, "the sender address"), match(RECEIPT, display, "the sender name")));
        }
        if (!labels.containsKey("RECEIPT") && statement == null) {
            add(labels, "BILL", first(match(BILL, title, "the subject"), match(BILL, sender, "the sender address"),
                    match(BILL, display, "the sender name"), match(BILL_NAME, display, "the sender name")));
        }

        String shipping = unless(NOT_SHIPPED, SHIPPED, title);
        add(labels, "SHIPPING", shipping);
        if (shipping == null && !NOT_SHIPPED.matcher(title).find()) add(labels, "ORDER", unless(NOT_ORDER, ORDER, title));
        add(labels, "SECURITY", unless(NOT_SECURITY, SECURITY, title));
        add(labels, "NOTIFICATION", match(NOTIFY, title, "the subject"));

        add(labels, "BANKING", first(domain(BANKS, domain),
                NOT_BANK.matcher(display).find() ? null : match(BANK, display, "the sender name"),
                NOT_BANK.matcher(sender.replace('-', ' ')).find() ? null : match(BANK, sender, "the sender address"),
                match(BANK_SUBJECT, title, "the subject")));
        add(labels, "INVESTING", domain(INVESTORS, domain));
        add(labels, "SHOPPING", domain(STORES.keySet(), domain));
        add(labels, "SOCIAL", first(domain(SOCIAL, domain), match(SOCIAL_NAME, display, "the sender name")));
        if (sender.equals("comments-noreply@docs.google.com")) {
            labels.put("DOCUMENT_COMMENT", "Google Docs comment-notification sender.");
        }
        if (domain.equals("google.com") && (mailbox.matches("drive-shares?-dm-noreply|comments-noreply")
                || DOC_NAME.matcher(display).find())) {
            add(labels, "DOCUMENT_COMMENT", match(COMMENT, title, "the subject"));
        }
        if (outlook && sender.equals("no-reply@sharepointonline.com") && OFFICE_MENTION.matcher(title).find()
                && !title.matches("(?i)^(?:re|fw|fwd):.*")) {
            labels.put("DOCUMENT_COMMENT", "Microsoft 365 sender and document-comment mention subject.");
        }
        if (outlookMeeting) {
            labels.put("CALENDAR_INVITE", "Outlook identified this message as a meeting request or cancellation.");
        } else if (sender.equals("calendar-notification@google.com") || CALENDAR_SCHEDULE.matcher(title).find()) {
            add(labels, "CALENDAR_INVITE", match(CALENDAR_SUBJECT, title, "the subject"));
        }

        if (correction != null) {
            labels.keySet().removeIf(label -> PURPOSES.contains(label) && !label.equals(correction));
            if (!correction.equals("CALENDAR_INVITE")) labels.remove("CALENDAR_INVITE");
        }
        return labels;
    }

    private static Pattern words(String alternatives) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
    }

    private static String unless(Pattern exclusion, Pattern signal, String subject) {
        return exclusion.matcher(subject).find() ? null : match(signal, subject, "the subject");
    }

    private static String match(Pattern pattern, String value, String field) {
        var match = pattern.matcher(value);
        if (!match.find()) return null;
        // Store rule evidence, never a variable order number or other subject text in the explanation.
        String phrase = match.group().toLowerCase(Locale.ROOT);
        if (pattern == SHIPPED && (phrase.startsWith("order") || phrase.startsWith("your order")))
            phrase = "order and shipping update";
        return "“" + phrase + "” matched in " + field + ".";
    }

    static String shoppingBrand(String address) {
        String sender=Objects.toString(address,"");
        String domain=sender.substring(sender.lastIndexOf('@')+1).toLowerCase(Locale.ROOT);
        return matchingDomain(STORES.keySet(),domain).map(STORES::get).orElse(null);
    }

    private static String domain(Collection<String> domains, String senderDomain) {
        return matchingDomain(domains,senderDomain).map(d -> d + " matched the sender domain.").orElse(null);
    }

    private static Optional<String> matchingDomain(Collection<String> domains,String senderDomain) {
        return domains.stream().filter(d -> senderDomain.equals(d) || senderDomain.endsWith("." + d)).findFirst();
    }

    private static void add(Map<String,String> labels, String label, String reason) {
        if (reason != null) labels.put(label, reason);
    }

    private static String first(String... reasons) {
        for (String reason : reasons) if (reason != null) return reason;
        return null;
    }
}
