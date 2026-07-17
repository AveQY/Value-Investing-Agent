package com.aweqy.aiagent.service;

import com.aweqy.aiagent.exception.ChatSessionAccessException;
import com.aweqy.aiagent.model.ChatSessionLockResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class SessionLockService {

    public static final String CLIENT_ID_HEADER = "X-Chat-Client-Id";
    public static final String LEASE_TOKEN_HEADER = "X-Chat-Lease-Token";
    public static final long LEASE_TTL_SECONDS = 30L;

    private static final Duration LEASE_TTL = Duration.ofSeconds(LEASE_TTL_SECONDS);
    private static final Pattern SAFE_CLIENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,120}$");
    private static final Pattern SAFE_LEASE_TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,120}$");

    private final Map<String, LockLease> leases = new ConcurrentHashMap<>();

    public synchronized ChatSessionLockResponse acquire(String chatId, String clientId) {
        String normalizedClientId = normalizeClientId(chatId, clientId);
        Instant now = Instant.now();
        LockLease activeLease = getActiveLease(chatId, now);

        if (activeLease != null && !activeLease.clientId().equals(normalizedClientId)) {
            throw buildException(
                    HttpStatus.LOCKED,
                    chatId,
                    "occupied",
                    "该会话正在被其他客户端使用，请稍后再试或新建对话。"
            );
        }

        LockLease nextLease = activeLease == null
                ? new LockLease(chatId, normalizedClientId, UUID.randomUUID().toString(), now, now.plus(LEASE_TTL))
                : activeLease.renew(now.plus(LEASE_TTL));
        leases.put(chatId, nextLease);
        return toResponse(nextLease);
    }

    public synchronized ChatSessionLockResponse heartbeat(String chatId, String clientId, String leaseToken) {
        Instant now = Instant.now();
        LockLease activeLease = requireMatchingLease(chatId, clientId, leaseToken, now);
        LockLease renewedLease = activeLease.renew(now.plus(LEASE_TTL));
        leases.put(chatId, renewedLease);
        return toResponse(renewedLease);
    }

    public synchronized boolean release(String chatId, String clientId, String leaseToken) {
        Instant now = Instant.now();
        LockLease activeLease = getActiveLease(chatId, now);
        if (activeLease == null) {
            return false;
        }

        if (!activeLease.clientId().equals(normalizeClientId(chatId, clientId, false))) {
            return false;
        }

        if (!activeLease.leaseToken().equals(normalizeLeaseToken(chatId, leaseToken, false))) {
            return false;
        }

        leases.remove(chatId);
        return true;
    }

    public synchronized void assertAccess(String chatId, String clientId, String leaseToken) {
        requireMatchingLease(chatId, clientId, leaseToken, Instant.now());
    }

    private LockLease requireMatchingLease(String chatId, String clientId, String leaseToken, Instant now) {
        String normalizedClientId = normalizeClientId(chatId, clientId);
        String normalizedLeaseToken = normalizeLeaseToken(chatId, leaseToken);
        LockLease activeLease = getActiveLease(chatId, now);

        if (activeLease == null) {
            throw buildException(
                    HttpStatus.CONFLICT,
                    chatId,
                    "lease_expired",
                    "当前会话占用已失效，请重新打开会话后再试。"
            );
        }

        if (!activeLease.clientId().equals(normalizedClientId)) {
            throw buildException(
                    HttpStatus.LOCKED,
                    chatId,
                    "occupied",
                    "该会话正在被其他客户端使用，请稍后再试或新建对话。"
            );
        }

        if (!activeLease.leaseToken().equals(normalizedLeaseToken)) {
            throw buildException(
                    HttpStatus.CONFLICT,
                    chatId,
                    "lease_invalid",
                    "当前会话占用令牌已失效，请重新打开会话后再试。"
            );
        }

        return activeLease;
    }

    private LockLease getActiveLease(String chatId, Instant now) {
        LockLease lease = leases.get(chatId);
        if (lease == null) {
            return null;
        }

        if (!lease.expiresAt().isAfter(now)) {
            leases.remove(chatId, lease);
            return null;
        }

        return lease;
    }

    private ChatSessionLockResponse toResponse(LockLease lease) {
        return new ChatSessionLockResponse(
                lease.chatId(),
                lease.clientId(),
                lease.leaseToken(),
                lease.acquiredAt(),
                lease.expiresAt(),
                LEASE_TTL_SECONDS
        );
    }

    private ChatSessionAccessException buildException(HttpStatus status,
                                                      String chatId,
                                                      String reason,
                                                      String message) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("chatId", chatId);
        details.put("reason", reason);
        details.put("leaseTtlSeconds", String.valueOf(LEASE_TTL_SECONDS));
        return new ChatSessionAccessException(status, message, details);
    }

    private String normalizeClientId(String chatId, String clientId) {
        return normalizeClientId(chatId, clientId, true);
    }

    private String normalizeClientId(String chatId, String clientId, boolean required) {
        String normalized = StringUtils.trimWhitespace(clientId);
        if (!StringUtils.hasText(normalized)) {
            if (required) {
                throw buildException(
                        HttpStatus.CONFLICT,
                        chatId,
                        "lease_required",
                        "当前会话需要先占用后再访问，请重新打开会话。"
                );
            }
            return "";
        }

        if (!SAFE_CLIENT_ID_PATTERN.matcher(normalized).matches()) {
            throw buildException(
                    HttpStatus.CONFLICT,
                    chatId,
                    "lease_invalid",
                    "clientId 格式不正确，请重新打开会话。"
            );
        }

        return normalized;
    }

    private String normalizeLeaseToken(String chatId, String leaseToken) {
        return normalizeLeaseToken(chatId, leaseToken, true);
    }

    private String normalizeLeaseToken(String chatId, String leaseToken, boolean required) {
        String normalized = StringUtils.trimWhitespace(leaseToken);
        if (!StringUtils.hasText(normalized)) {
            if (required) {
                throw buildException(
                        HttpStatus.CONFLICT,
                        chatId,
                        "lease_required",
                        "当前会话需要先占用后再访问，请重新打开会话。"
                );
            }
            return "";
        }

        if (!SAFE_LEASE_TOKEN_PATTERN.matcher(normalized).matches()) {
            throw buildException(
                    HttpStatus.CONFLICT,
                    chatId,
                    "lease_invalid",
                    "会话占用令牌格式不正确，请重新打开会话。"
            );
        }

        return normalized;
    }

    private record LockLease(String chatId,
                             String clientId,
                             String leaseToken,
                             Instant acquiredAt,
                             Instant expiresAt) {

        private LockLease renew(Instant nextExpiresAt) {
            return new LockLease(chatId, clientId, leaseToken, acquiredAt, nextExpiresAt);
        }
    }
}
