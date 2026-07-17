package com.aweqy.aiagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import lombok.extern.slf4j.Slf4j;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 基于文件持久化的对话记忆
 */
@Slf4j
public class FileBasedChatMemory implements ChatMemory {

    private final String baseDir;
    private static final Kryo KRYO = new Kryo();

    static {
        KRYO.setRegistrationRequired(false);
        KRYO.setInstantiatorStrategy(new StdInstantiatorStrategy());
    }

    public FileBasedChatMemory(String dir) {
        this.baseDir = dir;
        File dirFile = new File(dir);
        if (!dirFile.exists()) {
            dirFile.mkdirs();
        }
    }

    @Override
    public synchronized void add(String conversationId, List<Message> messages) {
        List<Message> conversationMessages = getOrCreateConversation(conversationId);
        conversationMessages.addAll(messages);
        saveConversation(conversationId, conversationMessages);
    }

    @Override
    public synchronized List<Message> get(String conversationId, int lastN) {
        List<Message> messages = getOrCreateConversation(conversationId);
        if (lastN <= 0 || messages.size() <= lastN) {
            return messages;
        }
        return new ArrayList<>(messages.subList(messages.size() - lastN, messages.size()));
    }

    @Override
    public synchronized void clear(String conversationId) {
        File file = getConversationFile(conversationId);
        if (file.exists()) {
            try {
                Files.delete(file.toPath());
            } catch (IOException e) {
                log.error("failed to delete chat history file: {}", file.getAbsolutePath(), e);
                throw new IllegalStateException("删除本地聊天记忆失败: " + conversationId, e);
            }
        }
    }

    public synchronized List<Message> getAll(String conversationId) {
        return List.copyOf(getOrCreateConversation(conversationId));
    }

    public synchronized boolean exists(String conversationId) {
        return getConversationFile(conversationId).exists();
    }

    public synchronized void replaceAll(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId must not be blank");
        saveConversation(conversationId, new ArrayList<>(messages));
    }

    public synchronized List<ConversationFile> listConversationFiles() {
        Path directory = Path.of(baseDir);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".kryo"))
                    .map(this::toConversationFile)
                    .filter(item -> item != null)
                    .sorted(Comparator
                            .comparing(ConversationFile::lastModifiedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(ConversationFile::conversationId))
                    .toList();
        } catch (IOException ex) {
            log.error("failed to list chat history files, baseDir={}", baseDir, ex);
            return List.of();
        }
    }

    private List<Message> getOrCreateConversation(String conversationId) {
        Assert.hasText(conversationId, "conversationId must not be blank");
        File file = getConversationFile(conversationId);
        List<Message> messages = new ArrayList<>();
        if (file.exists()) {
            try (Input input = new Input(new FileInputStream(file))) {
                messages = KRYO.readObject(input, ArrayList.class);
            }
            catch (IOException e) {
                log.error("failed to load chat history, conversationId={}", conversationId, e);
            }
        }
        return messages;
    }

    private void saveConversation(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId must not be blank");
        File file = getConversationFile(conversationId);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("failed to create chat history directory: {}", parent.getAbsolutePath());
        }
        try (Output output = new Output(new FileOutputStream(file))) {
            KRYO.writeObject(output, messages);
        }
        catch (IOException e) {
            log.error("failed to save chat history, conversationId={}", conversationId, e);
        }
    }

    private File getConversationFile(String conversationId) {
        return new File(baseDir, conversationId + ".kryo");
    }

    private ConversationFile toConversationFile(Path path) {
        String fileName = path.getFileName().toString();
        if (!fileName.endsWith(".kryo")) {
            return null;
        }

        String conversationId = fileName.substring(0, fileName.length() - 5);
        try {
            return new ConversationFile(
                    conversationId,
                    Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis())
            );
        } catch (IOException ex) {
            log.warn("failed to read chat history file metadata: {}", path, ex);
            return null;
        }
    }

    public record ConversationFile(String conversationId, Instant lastModifiedAt) {
    }
}
