package com.hmdp.profile.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiProfileResult {

    private String narrative;
    private List<ProfileTag> interestTags = new ArrayList<>();
    private String semanticQuery;
    private List<String> avoidTags = new ArrayList<>();
    private List<ProfileTag> adAffinity = new ArrayList<>();
    private String source;
    private String promptVersion;
    private String modelVersion;
}
