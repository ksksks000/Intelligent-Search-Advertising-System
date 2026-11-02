package com.hmdp.ad.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ad.dto.AdCtrSample;
import com.hmdp.utils.RedisConstants;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * CTR 分段保序回归（isotonic）校准：
 * 1. 按 raw 概率分 10 段，统计每段平均预测与真实点击率；
 * 2. 用 PAV（pool adjacent violators）保证映射单调；
 * 3. 线性插值输出校准概率。
 */
@Slf4j
@Service
public class AdCtrCalibrationService {

    private static final int BINS = 10;

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private ObjectMapper objectMapper;

    private volatile double[][] points = new double[][]{{0D, 0D}, {1D, 1D}};

    @PostConstruct
    public void load() {
        try {
            String json = redis.opsForValue().get(RedisConstants.AD_CTR_CALIBRATION_KEY);
            if (json != null) {
                points = objectMapper.readValue(json, double[][].class);
            }
        } catch (Exception e) {
            log.warn("加载 CTR 校准参数失败，使用默认恒等映射: {}", e.getMessage());
        }
    }

    public double calibrate(double raw) {
        return interpolate(points, clamp(raw));
    }

    public synchronized FitResult fit(List<AdCtrSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return new FitResult(0D, 0D, 0);
        }
        List<Bin> bins = new ArrayList<>();
        for (int i = 0; i < BINS; i++) {
            double low = i / (double) BINS;
            double high = (i + 1) / (double) BINS;
            double sumRaw = 0D;
            long clicks = 0;
            long count = 0;
            for (AdCtrSample sample : samples) {
                double raw = clamp(sample.getRaw());
                if ((i == BINS - 1 && raw >= low && raw <= high)
                        || (i < BINS - 1 && raw >= low && raw < high)) {
                    sumRaw += raw;
                    clicks += sample.getClicked() > 0 ? 1 : 0;
                    count++;
                }
            }
            if (count > 0) {
                bins.add(new Bin(sumRaw / count, clicks / (double) count, count));
            }
        }
        bins.sort(Comparator.comparingDouble(b -> b.raw));
        // PAV：相邻段若真实 CTR 逆序则合并并取加权平均
        List<Bin> merged = new ArrayList<>();
        for (Bin bin : bins) {
            merged.add(bin);
            while (merged.size() >= 2) {
                Bin last = merged.get(merged.size() - 1);
                Bin prev = merged.get(merged.size() - 2);
                if (prev.actual <= last.actual) {
                    break;
                }
                merged.remove(merged.size() - 1);
                merged.remove(merged.size() - 1);
                long count = prev.count + last.count;
                double raw = (prev.raw * prev.count + last.raw * last.count) / count;
                double actual = (prev.actual * prev.count + last.actual * last.count) / count;
                merged.add(new Bin(raw, actual, count));
            }
        }
        List<double[]> next = new ArrayList<>();
        next.add(new double[]{0D, 0D});
        for (Bin bin : merged) {
            next.add(new double[]{clamp(bin.raw), clamp(bin.actual)});
        }
        next.add(new double[]{1D, 1D});
        double[][] fitted = next.toArray(new double[0][]);
        double before = ece(samples, points);
        double after = ece(samples, fitted);
        points = fitted;
        persist(fitted);
        log.info("CTR 校准完成 samples={}, bins={}, ECE before={}, after={}",
                samples.size(), merged.size(), before, after);
        return new FitResult(before, after, samples.size());
    }

    public FitResult evaluate(List<AdCtrSample> samples) {
        return new FitResult(ece(samples, points), ece(samples, points), samples.size());
    }

    /**
     * 时间切分验证：前 train 条拟合，后 test 条独立评估。
     * before 使用恒等映射，after 使用训练集拟合映射；均只在 test 上计算。
     */
    public synchronized TimeSplitResult fitTimeSplit(List<AdCtrSample> train,
                                                     List<AdCtrSample> test, int bins) {
        if (train == null || train.isEmpty() || test == null || test.isEmpty()) {
            return new TimeSplitResult(0D, 0D, 0, bins);
        }
        double[][] fitted = buildMapping(train, bins);
        double[][] identity = new double[][]{{0D, 0D}, {1D, 1D}};
        double before = ece(test, identity, bins);
        double after = ece(test, fitted, bins);
        points = fitted;
        persist(fitted);
        log.info("CTR 时间切分校准 train={}, test={}, bins={}, ECE before={}, after={}",
                train.size(), test.size(), bins, before, after);
        return new TimeSplitResult(before, after, test.size(), bins);
    }

    private double[][] buildMapping(List<AdCtrSample> samples, int binCount) {
        int bins = Math.max(1, binCount);
        List<Bin> binList = new ArrayList<>();
        for (int i = 0; i < bins; i++) {
            double low = i / (double) bins;
            double high = (i + 1) / (double) bins;
            double sumRaw = 0D;
            long clicks = 0;
            long count = 0;
            for (AdCtrSample sample : samples) {
                double raw = clamp(sample.getRaw());
                if ((i == bins - 1 && raw >= low && raw <= high)
                        || (i < bins - 1 && raw >= low && raw < high)) {
                    sumRaw += raw;
                    clicks += sample.getClicked() > 0 ? 1 : 0;
                    count++;
                }
            }
            if (count > 0) {
                binList.add(new Bin(sumRaw / count, clicks / (double) count, count));
            }
        }
        binList.sort(Comparator.comparingDouble(b -> b.raw));
        List<Bin> merged = new ArrayList<>();
        for (Bin bin : binList) {
            merged.add(bin);
            while (merged.size() >= 2) {
                Bin last = merged.get(merged.size() - 1);
                Bin prev = merged.get(merged.size() - 2);
                if (prev.actual <= last.actual) {
                    break;
                }
                merged.remove(merged.size() - 1);
                merged.remove(merged.size() - 1);
                long count = prev.count + last.count;
                double raw = (prev.raw * prev.count + last.raw * last.count) / count;
                double actual = (prev.actual * prev.count + last.actual * last.count) / count;
                merged.add(new Bin(raw, actual, count));
            }
        }
        List<double[]> next = new ArrayList<>();
        next.add(new double[]{0D, 0D});
        for (Bin bin : merged) {
            next.add(new double[]{clamp(bin.raw), clamp(bin.actual)});
        }
        next.add(new double[]{1D, 1D});
        return next.toArray(new double[0][]);
    }

    private double ece(List<AdCtrSample> samples, double[][] mapping, int binCount) {
        int bins = Math.max(1, binCount);
        double total = samples.size();
        if (total <= 0) {
            return 0D;
        }
        double sum = 0D;
        for (int i = 0; i < bins; i++) {
            double low = i / (double) bins;
            double high = (i + 1) / (double) bins;
            double predictedSum = 0D;
            double actualSum = 0D;
            int count = 0;
            for (AdCtrSample sample : samples) {
                double raw = clamp(sample.getRaw());
                if ((i == bins - 1 && raw >= low && raw <= high)
                        || (i < bins - 1 && raw >= low && raw < high)) {
                    predictedSum += interpolate(mapping, raw);
                    actualSum += sample.getClicked() > 0 ? 1D : 0D;
                    count++;
                }
            }
            if (count > 0) {
                sum += Math.abs(predictedSum / count - actualSum / count) * count;
            }
        }
        return sum / total;
    }

    private double ece(List<AdCtrSample> samples, double[][] mapping) {
        double total = samples.size();
        if (total <= 0) {
            return 0D;
        }
        double sum = 0D;
        for (int i = 0; i < BINS; i++) {
            double low = i / (double) BINS;
            double high = (i + 1) / (double) BINS;
            double predictedSum = 0D;
            double actualSum = 0D;
            int count = 0;
            for (AdCtrSample sample : samples) {
                double raw = clamp(sample.getRaw());
                if ((i == BINS - 1 && raw >= low && raw <= high)
                        || (i < BINS - 1 && raw >= low && raw < high)) {
                    predictedSum += interpolate(mapping, raw);
                    actualSum += sample.getClicked() > 0 ? 1D : 0D;
                    count++;
                }
            }
            if (count > 0) {
                sum += Math.abs(predictedSum / count - actualSum / count) * count;
            }
        }
        return sum / total;
    }

    private double interpolate(double[][] mapping, double raw) {
        if (mapping == null || mapping.length == 0) {
            return raw;
        }
        if (raw <= mapping[0][0]) {
            return mapping[0][1];
        }
        for (int i = 1; i < mapping.length; i++) {
            if (raw <= mapping[i][0]) {
                double x0 = mapping[i - 1][0];
                double y0 = mapping[i - 1][1];
                double x1 = mapping[i][0];
                double y1 = mapping[i][1];
                if (x1 <= x0) {
                    return y1;
                }
                return y0 + (y1 - y0) * (raw - x0) / (x1 - x0);
            }
        }
        return mapping[mapping.length - 1][1];
    }

    private void persist(double[][] fitted) {
        try {
            redis.opsForValue().set(RedisConstants.AD_CTR_CALIBRATION_KEY,
                    objectMapper.writeValueAsString(fitted), 7, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("持久化 CTR 校准参数失败: {}", e.getMessage());
        }
    }

    private double clamp(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private static final class Bin {
        private final double raw;
        private final double actual;
        private final long count;

        private Bin(double raw, double actual, long count) {
            this.raw = raw;
            this.actual = actual;
            this.count = count;
        }
    }

    @Data
    public static class TimeSplitResult {
        private final double eceBefore;
        private final double eceAfter;
        private final int samples;
        private final int bins;
    }

    @Data
    public static class FitResult {
        private final double eceBefore;
        private final double eceAfter;
        private final int samples;
    }
}
