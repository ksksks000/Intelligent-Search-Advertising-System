package com.hmdp.profile.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ProfileTagsRequest {

    private List<String> tags = new ArrayList<>();
}
