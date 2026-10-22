package com.aweqy.aiagent.service;

import com.aweqy.aiagent.config.ChatModelProperties;
import com.aweqy.aiagent.exception.UpstreamModelServiceException;
import com.aweqy.aiagent.model.ChatModelCatalogResponse;
import com.aweqy.aiagent.model.ChatModelSummary;
import com.aweqy.aiagent.model.CreateChatModelRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class ChatModelRegistryService {

    private static final String OPENAI_COMPATIBLE_PROVIDER = "openai-compatible";
    private static final String OPENAI_V1_SUFFIX = "/v1";
    private static final String CHAT_COMPLETIONS_SUFFIX = "/chat/completions";
    private static final int AI_CONNECT_TIMEOUT_MS = 30_000;
    private static final int AI_READ_TIMEOUT_MS = 180_000;

    private final ChatModelProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, StoredChatModel> customModels = new LinkedHashMap<>();
    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    public ChatModelRegistryService(ChatModelProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void initialize() {
        loadCustomModels();
    }

    public synchronized ChatModelCatalogResponse listModels() {
        String defaultModelId = resolveDefaultModelId();
        List<ChatModelSummary> models = collectAvailableModels().stream()
                .map(model -> toSummary(model, Objects.equals(model.id(), defaultModelId)))
                .toList();
        return new ChatModelCatalogResponse(defaultModelId, models, Instant.now());
    }

    public synchronized ChatModelSummary addCustomModel(CreateChatModelRequest request) {
        String modelId = request.modelId().trim();
        StoredChatModel storedModel = new StoredChatModel(
                generateCustomModelId(modelId, modelId),
                modelId,
                OPENAI_COMPATIBLE_PROVIDER,
                request.baseUrl().trim(),
                request.apiKey().trim(),
                request.modelId().trim(),
                false,
                true
        );

        customModels.put(storedModel.id(), storedModel);
        persistCustomModels();
        chatModelCache.remove(storedModel.id());

        boolean isDefaultModel = Objects.equals(storedModel.id(), resolveDefaultModelId());
        return toSummary(storedModel, isDefaultModel);
    }

    public List<String> discoverModels(String baseUrl, String apiKey) {
        String normalizedBaseUrl = StringUtils.trimWhitespace(baseUrl);
        String normalizedApiKey = StringUtils.trimWhitespace(apiKey);
        if (!StringUtils.hasText(normalizedBaseUrl) || !StringUtils.hasText(normalizedApiKey)) {
            throw new IllegalArgumentException("Base URL 和 API Key 不能为空");
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(AI_CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(AI_READ_TIMEOUT_MS);
        try {
            JsonNode payload = RestClient.builder()
                    .requestFactory(requestFactory)
                    .build()
                    .get()
                    .uri(buildModelsUrl(normalizedBaseUrl))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + normalizedApiKey)
                    .retrieve()
                    .body(JsonNode.class);
            if (payload == null || !payload.path("data").isArray()) {
                throw new UpstreamModelServiceException("模型服务返回格式不兼容，未找到 data 模型列表");
            }
            List<String> modelIds = StreamSupport.stream(
                            java.util.Spliterators.spliteratorUnknownSize(payload.path("data").elements(), java.util.Spliterator.ORDERED),
                            false)
                    .map(node -> node.path("id").asText(""))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            if (modelIds.isEmpty()) {
                throw new UpstreamModelServiceException("模型服务返回的模型列表为空");
            }
            return modelIds;
        } catch (UpstreamModelServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Failed to discover models from {}", normalizedBaseUrl, ex);
            throw new UpstreamModelServiceException("获取模型列表失败，请检查 Base URL、API Key 和供应商接口是否兼容。", ex);
        }
    }

    public synchronized String resolveModelId(String requestedModelId) {
        return resolveModel(requestedModelId).id();
    }

    public synchronized ChatModelSummary getModelSummary(String requestedModelId) {
        StoredChatModel resolvedModel = resolveModel(requestedModelId);
        return toSummary(resolvedModel, Objects.equals(resolvedModel.id(), resolveDefaultModelId()));
    }

    public ChatModel getChatModel(String requestedModelId) {
        String resolvedModelId = resolveModelId(requestedModelId);
        return chatModelCache.computeIfAbsent(resolvedModelId, this::createChatModel);
    }

    public ChatModel getInternalTaskChatModel(String requestedModelId) {
        String resolvedModelId = resolveInternalTaskModelId(requestedModelId);
        return chatModelCache.computeIfAbsent(resolvedModelId, this::createChatModel);
    }

    public synchronized String resolveInternalTaskModelId(String requestedModelId) {
        String defaultModelId = resolveDefaultModelId();
        if (!StringUtils.hasText(requestedModelId)) {
            return defaultModelId;
        }

        String resolvedRequestedModelId = resolveModelId(requestedModelId);
        if (Objects.equals(resolvedRequestedModelId, defaultModelId)) {
            return resolvedRequestedModelId;
        }

        // Internal routing / planning tasks are deterministic control-plane work.
        // Prefer the default model here to avoid provider-specific instability from
        // user-selected models affecting tool routing and pre-analysis preparation.
        return defaultModelId;
    }

    private synchronized StoredChatModel resolveModel(String requestedModelId) {
        Map<String, StoredChatModel> modelsById = indexById(collectAvailableModels());
        if (StringUtils.hasText(requestedModelId)) {
            StoredChatModel requested = modelsById.get(requestedModelId.trim());
            if (requested != null) {
                return requested;
            }
            log.warn("Requested chat model [{}] was not found, falling back to default model.", requestedModelId);
        }

        String defaultModelId = resolveDefaultModelId(modelsById);
        StoredChatModel defaultModel = modelsById.get(defaultModelId);
        if (defaultModel != null) {
            return defaultModel;
        }

        throw new IllegalStateException("当前没有可用的聊天模型，请先在后端配置或新增模型。");
    }

    private synchronized String resolveDefaultModelId() {
        return resolveDefaultModelId(indexById(collectAvailableModels()));
    }

    private String resolveDefaultModelId(Map<String, StoredChatModel> modelsById) {
        if (StringUtils.hasText(properties.getDefaultModelId()) && modelsById.containsKey(properties.getDefaultModelId().trim())) {
            return properties.getDefaultModelId().trim();
        }
        return modelsById.keySet().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("当前没有可用的聊天模型，请先配置 app.chat.models.presets 或新增模型。"));
    }

    private synchronized List<StoredChatModel> collectAvailableModels() {
        List<StoredChatModel> models = new ArrayList<>();
        for (ChatModelProperties.PresetModel preset : properties.getPresets()) {
            StoredChatModel storedModel = toStoredPreset(preset);
            if (storedModel != null) {
                models.add(storedModel);
            }
        }
        models.addAll(customModels.values().stream()
                .filter(model -> StringUtils.hasText(model.apiKey()))
                .sorted(Comparator.comparing(StoredChatModel::name, String.CASE_INSENSITIVE_ORDER))
                .toList());
        return models;
    }

    private StoredChatModel toStoredPreset(ChatModelProperties.PresetModel preset) {
        if (preset == null || !preset.isEnabled()) {
            return null;
        }
        if (!StringUtils.hasText(preset.getId())
                || !StringUtils.hasText(preset.getName())
                || !StringUtils.hasText(preset.getBaseUrl())
                || !StringUtils.hasText(preset.getApiKey())
                || !StringUtils.hasText(preset.getProviderModelId())) {
            return null;
        }
        return new StoredChatModel(
                preset.getId().trim(),
                preset.getName().trim(),
                StringUtils.hasText(preset.getProvider()) ? preset.getProvider().trim() : OPENAI_COMPATIBLE_PROVIDER,
                preset.getBaseUrl().trim(),
                preset.getApiKey().trim(),
                preset.getProviderModelId().trim(),
                true,
                false
        );
    }

    private synchronized void loadCustomModels() {
        customModels.clear();
        Path storagePath = resolveStoragePath();
        if (!Files.exists(storagePath)) {
            return;
        }

        try {
            List<StoredChatModel> storedModels = objectMapper.readValue(storagePath.toFile(), new TypeReference<>() {
            });
            for (StoredChatModel model : storedModels) {
                if (model == null
                        || !StringUtils.hasText(model.id())
                        || !StringUtils.hasText(model.name())
                        || !StringUtils.hasText(model.baseUrl())
                        || !StringUtils.hasText(model.apiKey())
                        || !StringUtils.hasText(model.providerModelId())) {
                    continue;
                }
                customModels.put(model.id().trim(), new StoredChatModel(
                        model.id().trim(),
                        model.name().trim(),
                        StringUtils.hasText(model.provider()) ? model.provider().trim() : OPENAI_COMPATIBLE_PROVIDER,
                        model.baseUrl().trim(),
                        model.apiKey().trim(),
                        model.providerModelId().trim(),
                        false,
                        true
                ));
            }
        } catch (IOException ex) {
            log.warn("Failed to load custom chat models from {}", storagePath, ex);
        }
    }

    private synchronized void persistCustomModels() {
        Path storagePath = resolveStoragePath();
        try {
            Files.createDirectories(storagePath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), new ArrayList<>(customModels.values()));
        } catch (IOException ex) {
            throw new IllegalStateException("保存自定义模型失败：" + ex.getMessage(), ex);
        }
    }

    private Path resolveStoragePath() {
        Path path = Paths.get(StringUtils.hasText(properties.getStorageFile())
                ? properties.getStorageFile().trim()
                : "tmp/chat-models/custom-models.json");
        return path.isAbsolute() ? path : path.toAbsolutePath().normalize();
    }

    private Map<String, StoredChatModel> indexById(List<StoredChatModel> models) {
        Map<String, StoredChatModel> indexed = new LinkedHashMap<>();
        for (StoredChatModel model : models) {
            indexed.put(model.id(), model);
        }
        return indexed;
    }

    private String buildModelsUrl(String baseUrl) {
        String normalized = StringUtils.trimWhitespace(baseUrl);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        }
        if (normalized.endsWith("/models")) {
            return normalized;
        }
        if (normalized.endsWith(OPENAI_V1_SUFFIX)) {
            return normalized + "/models";
        }
        return normalized + "/v1/models";
    }

    private ChatModel createChatModel(String modelId) {
        StoredChatModel model = resolveModel(modelId);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(AI_CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(AI_READ_TIMEOUT_MS);
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(normalizeBaseUrl(model.baseUrl()))
                .apiKey(model.apiKey())
                .restClientBuilder(restClientBuilder)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model.providerModelId())
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    private ChatModelSummary toSummary(StoredChatModel model, boolean defaultModel) {
        return new ChatModelSummary(
                model.id(),
                model.name(),
                model.provider(),
                model.baseUrl(),
                model.providerModelId(),
                model.preset(),
                model.custom(),
                defaultModel,
                maskApiKey(model.apiKey())
        );
    }

    private String generateCustomModelId(String name, String providerModelId) {
        String nameSlug = slugify(name);
        String providerSlug = slugify(providerModelId);
        String baseId = StreamJoiner.join("custom", nameSlug, providerSlug);
        if (!StringUtils.hasText(baseId)) {
            baseId = "custom-model";
        }

        String candidate = baseId;
        int suffix = 2;
        while (containsModelId(candidate)) {
            candidate = baseId + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private boolean containsModelId(String candidateId) {
        List<StoredChatModel> availableModels = collectAvailableModels();
        return availableModels.stream().anyMatch(model -> model.id().equals(candidateId))
                || customModels.containsKey(candidateId);
    }

    private String slugify(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('/', '-')
                .replace('\\', '-')
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return normalized.length() > 48 ? normalized.substring(0, 48) : normalized;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = StringUtils.trimWhitespace(baseUrl);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(CHAT_COMPLETIONS_SUFFIX)) {
            normalized = normalized.substring(0, normalized.length() - CHAT_COMPLETIONS_SUFFIX.length());
        }
        if (normalized.endsWith(OPENAI_V1_SUFFIX)) {
            normalized = normalized.substring(0, normalized.length() - OPENAI_V1_SUFFIX.length());
        }
        return normalized;
    }

    private String maskApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return null;
        }
        String normalized = apiKey.trim();
        if (normalized.length() <= 8) {
            return normalized.charAt(0) + "***" + normalized.charAt(normalized.length() - 1);
        }
        return normalized.substring(0, 6) + "..." + normalized.substring(normalized.length() - 4);
    }

    private record StoredChatModel(String id,
                                   String name,
                                   String provider,
                                   String baseUrl,
                                   String apiKey,
                                   String providerModelId,
                                   boolean preset,
                                   boolean custom) {
    }

    private static final class StreamJoiner {

        private StreamJoiner() {
        }

        private static String join(String... parts) {
            return java.util.Arrays.stream(parts)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .reduce((left, right) -> left + "-" + right)
                    .orElse("");
        }
    }
}
