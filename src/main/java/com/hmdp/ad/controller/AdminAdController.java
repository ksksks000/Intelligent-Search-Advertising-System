package com.hmdp.ad.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ad.entity.AdBillingLog;
import com.hmdp.ad.entity.AdCampaign;
import com.hmdp.ad.entity.AdExposureLog;
import com.hmdp.ad.entity.Advertiser;
import com.hmdp.ad.service.AdCampaignService;
import com.hmdp.dto.Result;
import com.hmdp.mapper.AdBillingLogMapper;
import com.hmdp.mapper.AdExposureLogMapper;
import com.hmdp.mapper.AdvertiserMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 广告管理端：广告主、计划、预算充值、上下架、最小看板。由 AdminAuthInterceptor 校验 token。
 */
@RestController
@RequestMapping("/admin/ad")
public class AdminAdController {

    @Resource
    private AdvertiserMapper advertiserMapper;
    @Resource
    private AdCampaignService campaignService;
    @Resource
    private AdBillingLogMapper billingLogMapper;
    @Resource
    private AdExposureLogMapper exposureLogMapper;

    @PostMapping("/advertiser")
    public Result createAdvertiser(@RequestBody Advertiser advertiser) {
        if (advertiser.getName() == null || advertiser.getName().trim().isEmpty()) {
            return Result.fail("广告主名称不能为空");
        }
        if (advertiser.getStatus() == null) {
            advertiser.setStatus(Advertiser.STATUS_ACTIVE);
        }
        if (advertiser.getBalance() == null) {
            advertiser.setBalance(0L);
        }
        advertiserMapper.insert(advertiser);
        return Result.ok(advertiser);
    }

    @PostMapping("/campaign")
    public Result createCampaign(@RequestBody AdCampaign campaign) {
        if (campaign.getAdvertiserId() == null || campaign.getTitle() == null
                || campaign.getBid() == null) {
            return Result.fail("advertiserId/title/bid不能为空");
        }
        return Result.ok(campaignService.create(campaign));
    }

    @GetMapping("/campaigns")
    public Result campaigns() {
        return Result.ok(campaignService.list());
    }

    @PostMapping("/campaign/{id}/status")
    public Result status(@PathVariable("id") Long id, @RequestParam("status") Integer status) {
        return campaignService.updateStatus(id, status) ? Result.ok() : Result.fail("计划不存在");
    }

    @PostMapping("/campaign/{id}/topup")
    public Result topup(@PathVariable("id") Long id, @RequestParam("amount") Long amount) {
        if (amount == null || amount <= 0) {
            return Result.fail("amount必须大于0");
        }
        return Result.ok(campaignService.topup(id, amount));
    }

    @GetMapping("/dashboard")
    public Result dashboard(@RequestParam(value = "campaignId", required = false) Long campaignId) {
        QueryWrapper<AdBillingLog> billing = new QueryWrapper<>();
        billing.ge("event_time", LocalDate.now().atStartOfDay());
        if (campaignId != null) {
            billing.eq("campaign_id", campaignId);
        }
        List<AdBillingLog> logs = billingLogMapper.selectList(billing);
        QueryWrapper<AdExposureLog> exposure = new QueryWrapper<>();
        exposure.ge("exposed_at", LocalDate.now().atStartOfDay());
        if (campaignId != null) {
            exposure.eq("campaign_id", campaignId);
        }
        List<AdExposureLog> exposures = exposureLogMapper.selectList(exposure);
        long spend = logs.stream().mapToLong(l -> l.getCost() == null ? 0 : l.getCost()).sum();
        long clicks = exposures.stream().filter(e -> Integer.valueOf(1).equals(e.getClicked())).count();
        long conversions = exposures.stream()
                .filter(e -> Integer.valueOf(1).equals(e.getConverted())).count();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", LocalDate.now().toString());
        data.put("impressions", exposures.size());
        data.put("clicks", clicks);
        data.put("conversions", conversions);
        data.put("spendFen", spend);
        data.put("ctr", exposures.isEmpty() ? 0D : (double) clicks / exposures.size());
        data.put("cvr", clicks == 0 ? 0D : (double) conversions / clicks);
        return Result.ok(data);
    }
}
