package com.hmdp.search.controller;

import com.hmdp.dto.Result;
import com.hmdp.search.dto.SearchRequestDTO;
import com.hmdp.search.dto.SearchResultDTO;
import com.hmdp.search.service.ActivitySearchService;
import com.hmdp.search.service.SearchHotWordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/search")
public class SearchController {

    @Resource
    private ActivitySearchService activitySearchService;

    @Resource
    private SearchHotWordService hotWordService;

    @PostMapping("/activity")
    public Result search(@RequestBody(required = false) SearchRequestDTO request) {
        SearchResultDTO result = activitySearchService.search(request);
        return Result.ok(result);
    }

    @GetMapping("/hot")
    public Result hot(@RequestParam(value = "limit", defaultValue = "10") Integer limit) {
        return Result.ok(hotWordService.list(limit == null ? 10 : limit));
    }
}
