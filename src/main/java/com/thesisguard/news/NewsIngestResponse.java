package com.thesisguard.news;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NewsIngestResponse(@JsonProperty("new_items_count") int newItemsCount) {
}
