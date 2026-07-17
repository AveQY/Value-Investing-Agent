package com.aweqy.aiagent.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat.models")
public class ChatModelProperties {

    private String defaultModelId;

    private String storageFile = "tmp/chat-models/custom-models.json";

    private List<PresetModel> presets = new ArrayList<>();

    public String getDefaultModelId() {
        return defaultModelId;
    }

    public void setDefaultModelId(String defaultModelId) {
        this.defaultModelId = defaultModelId;
    }

    public String getStorageFile() {
        return storageFile;
    }

    public void setStorageFile(String storageFile) {
        this.storageFile = storageFile;
    }

    public List<PresetModel> getPresets() {
        return presets;
    }

    public void setPresets(List<PresetModel> presets) {
        this.presets = presets == null ? new ArrayList<>() : new ArrayList<>(presets);
    }

    public static class PresetModel {

        private String id;

        private String name;

        private String provider = "openai-compatible";

        private String baseUrl;

        private String apiKey;

        private String providerModelId;

        private boolean enabled = true;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getProviderModelId() {
            return providerModelId;
        }

        public void setProviderModelId(String providerModelId) {
            this.providerModelId = providerModelId;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
