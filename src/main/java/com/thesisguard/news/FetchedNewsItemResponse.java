package com.thesisguard.news;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.thesisguard.openbb.OpenBbFilingItem;
import com.thesisguard.openbb.OpenBbInsiderTransaction;
import com.thesisguard.openbb.OpenBbNewsItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public record FetchedNewsItemResponse(
        String symbol,
        String title,
        String url,
        @JsonProperty("published_date") LocalDate publishedDate,
        String source,
        String summary
) {

    private static final String SEC_SOURCE = "SEC EDGAR";

    private static final Map<String, String> FORM_8K_ITEM_DESCRIPTIONS = Map.ofEntries(
            Map.entry("1.01", "Entry into a Material Definitive Agreement"),
            Map.entry("1.02", "Termination of a Material Definitive Agreement"),
            Map.entry("1.03", "Bankruptcy or Receivership"),
            Map.entry("1.05", "Material Cybersecurity Incident"),
            Map.entry("2.01", "Completion of Acquisition or Disposition of Assets"),
            Map.entry("2.02", "Results of Operations and Financial Condition"),
            Map.entry("2.03", "Creation of a Direct Financial Obligation"),
            Map.entry("2.04", "Triggering Events That Accelerate a Financial Obligation"),
            Map.entry("2.05", "Costs Associated with Exit or Disposal Activities"),
            Map.entry("2.06", "Material Impairments"),
            Map.entry("3.01", "Notice of Delisting or Failure to Satisfy a Listing Rule"),
            Map.entry("3.02", "Unregistered Sales of Equity Securities"),
            Map.entry("4.01", "Changes in Registrant's Certifying Accountant"),
            Map.entry("4.02", "Non-Reliance on Previously Issued Financial Statements"),
            Map.entry("5.01", "Changes in Control of Registrant"),
            Map.entry("5.02", "Departure or Appointment of Directors or Officers"),
            Map.entry("5.03", "Amendments to Articles of Incorporation or Bylaws"),
            Map.entry("5.07", "Submission of Matters to a Vote of Security Holders"),
            Map.entry("7.01", "Regulation FD Disclosure"),
            Map.entry("8.01", "Other Events"),
            Map.entry("9.01", "Financial Statements and Exhibits")
    );

    public static FetchedNewsItemResponse from(OpenBbNewsItem item) {
        return new FetchedNewsItemResponse(
                item.symbol(),
                item.title(),
                item.url(),
                item.date() != null ? item.date().toLocalDate() : null,
                item.source(),
                item.summary()
        );
    }

    public static FetchedNewsItemResponse fromFiling(String symbol, OpenBbFilingItem filing) {
        String formType = filing.reportType() == null ? "SEC filing" : filing.reportType();
        String itemDescriptions = describeFilingItems(filing.items());
        String title = itemDescriptions.isEmpty()
                ? "SEC %s filing".formatted(formType)
                : "SEC %s filing: %s".formatted(formType, itemDescriptions);
        String summary = "Form %s filed with the SEC%s.%s".formatted(
                formType,
                filing.filingDate() != null ? " on " + filing.filingDate() : "",
                itemDescriptions.isEmpty() ? "" : " Reported items: " + itemDescriptions + ".");
        String url = filing.filingDetailUrl() != null ? filing.filingDetailUrl() : filing.reportUrl();
        return new FetchedNewsItemResponse(symbol, title, url, filing.filingDate(), SEC_SOURCE, summary);
    }

    public static FetchedNewsItemResponse fromInsider(OpenBbInsiderTransaction tx) {
        boolean acquisition = "Acquisition".equalsIgnoreCase(tx.acquisitionOrDisposition());
        String role = insiderRole(tx);
        StringBuilder title = new StringBuilder("Insider transaction (Form 4): ").append(tx.ownerName());
        if (role != null) {
            title.append(" (").append(role).append(")");
        }
        title.append(acquisition ? " acquired " : " disposed of ");
        title.append(formatShares(tx.securitiesTransacted())).append(" shares");
        if (tx.securityType() != null) {
            title.append(" of ").append(tx.securityType());
        }
        if (tx.transactionPrice() != null && tx.transactionPrice().signum() > 0) {
            title.append(" at $").append(tx.transactionPrice().stripTrailingZeros().toPlainString());
        }

        StringBuilder summary = new StringBuilder();
        if (tx.transactionType() != null) {
            summary.append(tx.transactionType()).append(".");
        }
        if (tx.transactionDate() != null) {
            summary.append(" Transaction date: ").append(tx.transactionDate()).append(".");
        }
        if (tx.securitiesOwned() != null) {
            summary.append(" Securities owned after transaction: ").append(formatShares(tx.securitiesOwned())).append(".");
        }
        if (tx.footnote() != null && !tx.footnote().isBlank()) {
            summary.append(" ").append(tx.footnote());
        }
        return new FetchedNewsItemResponse(
                tx.symbol(),
                title.toString(),
                tx.filingUrl(),
                tx.filingDate(),
                SEC_SOURCE,
                summary.toString().trim()
        );
    }

    private static String describeFilingItems(String items) {
        if (items == null || items.isBlank()) return "";
        return Arrays.stream(items.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .map(code -> FORM_8K_ITEM_DESCRIPTIONS.getOrDefault(code, "Item " + code))
                .collect(Collectors.joining("; "));
    }

    private static String insiderRole(OpenBbInsiderTransaction tx) {
        if (tx.ownerTitle() != null && !tx.ownerTitle().isBlank()) return tx.ownerTitle();
        if (Boolean.TRUE.equals(tx.director())) return "Director";
        if (Boolean.TRUE.equals(tx.officer())) return "Officer";
        return null;
    }

    private static String formatShares(BigDecimal value) {
        if (value == null) return "an unknown number of";
        return String.format("%,d", value.longValue());
    }
}
