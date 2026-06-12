package com.thesisguard.news;

import com.thesisguard.openbb.OpenBbFilingItem;
import com.thesisguard.openbb.OpenBbInsiderTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FetchedNewsItemResponseTest {

    @Test
    void fromFilingMapsItemCodesToDescriptions() {
        OpenBbFilingItem filing = new OpenBbFilingItem(
                LocalDate.of(2026, 4, 30),
                "8-K",
                "https://www.sec.gov/Archives/report.htm",
                "https://www.sec.gov/Archives/index.htm",
                "8-K",
                "2.02,9.01"
        );

        FetchedNewsItemResponse response = FetchedNewsItemResponse.fromFiling("AAPL", filing);

        assertEquals("AAPL", response.symbol());
        assertEquals("SEC 8-K filing: Results of Operations and Financial Condition; Financial Statements and Exhibits", response.title());
        assertEquals(LocalDate.of(2026, 4, 30), response.publishedDate());
        assertEquals("SEC EDGAR", response.source());
        assertEquals("https://www.sec.gov/Archives/index.htm", response.url());
        assertTrue(response.summary().contains("filed with the SEC on 2026-04-30"));
    }

    @Test
    void fromFilingHandlesUnknownItemCodesAndMissingDetailUrl() {
        OpenBbFilingItem filing = new OpenBbFilingItem(
                LocalDate.of(2026, 5, 1),
                "8-K",
                "https://www.sec.gov/Archives/report.htm",
                null,
                "8-K",
                "6.05"
        );

        FetchedNewsItemResponse response = FetchedNewsItemResponse.fromFiling("NVDA", filing);

        assertEquals("SEC 8-K filing: Item 6.05", response.title());
        assertEquals("https://www.sec.gov/Archives/report.htm", response.url());
    }

    @Test
    void fromInsiderBuildsReadableSaleTitle() {
        OpenBbInsiderTransaction tx = new OpenBbInsiderTransaction(
                "AAPL",
                LocalDate.of(2026, 5, 29),
                LocalDate.of(2026, 5, 27),
                "LEVINSON ARTHUR D",
                null,
                true,
                null,
                "Open market or private sale of non-derivative or derivative security",
                "Disposition",
                "Common Stock",
                new BigDecimal("50000.0"),
                new BigDecimal("3764576.0"),
                new BigDecimal("311.02"),
                "https://www.sec.gov/Archives/form4.xml",
                null
        );

        FetchedNewsItemResponse response = FetchedNewsItemResponse.fromInsider(tx);

        assertEquals(
                "Insider transaction (Form 4): LEVINSON ARTHUR D (Director) disposed of 50,000 shares of Common Stock at $311.02",
                response.title());
        assertEquals(LocalDate.of(2026, 5, 29), response.publishedDate());
        assertEquals("SEC EDGAR", response.source());
        assertTrue(response.summary().contains("Securities owned after transaction: 3,764,576."));
    }

    @Test
    void fromInsiderOmitsZeroPriceAndUsesOwnerTitle() {
        OpenBbInsiderTransaction tx = new OpenBbInsiderTransaction(
                "AAPL",
                LocalDate.of(2026, 5, 29),
                LocalDate.of(2026, 5, 27),
                "DOE JANE",
                "Chief Financial Officer",
                null,
                true,
                "Grant, award or other acquisition",
                "Acquisition",
                "Common Stock",
                new BigDecimal("1000"),
                new BigDecimal("20000"),
                BigDecimal.ZERO,
                "https://www.sec.gov/Archives/form4.xml",
                "Granted under the equity incentive plan."
        );

        FetchedNewsItemResponse response = FetchedNewsItemResponse.fromInsider(tx);

        assertEquals(
                "Insider transaction (Form 4): DOE JANE (Chief Financial Officer) acquired 1,000 shares of Common Stock",
                response.title());
        assertTrue(response.summary().contains("Granted under the equity incentive plan."));
    }
}
