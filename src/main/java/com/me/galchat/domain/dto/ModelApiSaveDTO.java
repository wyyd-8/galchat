package com.me.galchat.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

@Data
@Accessors(chain = true)
public class ModelApiSaveDTO {
    private String name;
    private String baseUrl;
    private String modelName;
    private String apiKey;
    private Map<String, Object> requestOverrides;
}
