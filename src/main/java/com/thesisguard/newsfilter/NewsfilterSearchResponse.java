package com.thesisguard.newsfilter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NewsfilterSearchResponse(List<NewsfilterArticle> articles) {}
