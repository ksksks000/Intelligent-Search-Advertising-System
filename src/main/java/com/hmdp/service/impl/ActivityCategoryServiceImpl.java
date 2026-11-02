package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ActivityCategory;
import com.hmdp.mapper.ActivityCategoryMapper;
import com.hmdp.service.IActivityCategoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ActivityCategoryServiceImpl
        extends ServiceImpl<ActivityCategoryMapper, ActivityCategory>
        implements IActivityCategoryService {

    @Override
    public List<ActivityCategory> listSorted() {
        return query().orderByAsc("sort").list();
    }
}
