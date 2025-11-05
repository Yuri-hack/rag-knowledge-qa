package io.github.yuri_hack.rag_knowledge_qa.document;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件处理器工厂 - 负责管理和分发文件处理器
 */
@Slf4j
@Component
public class FileProcessorFactory {

    private final Map<String, FileProcessor> processorMap = new ConcurrentHashMap<>();

    /**
     * 构造函数注入所有处理器
     */
    public FileProcessorFactory(List<FileProcessor> processors) {
        for (FileProcessor processor : processors) {
            registerProcessor(processor);
        }
        log.info("文件处理器工厂初始化完成，注册了 {} 个处理器: {}",
                processorMap.size(), processorMap.keySet());
    }

    /**
     * 注册处理器
     */
    private void registerProcessor(FileProcessor processor) {
        for (String fileType : processor.getSupportedFileTypes()) {
            String key = fileType.toLowerCase();
            if (processorMap.containsKey(key)) {
                log.warn("文件类型 '{}' 的处理器已存在: {} -> {}, 将被覆盖",
                        key, processorMap.get(key).getProcessorName(),
                        processor.getProcessorName());
            }
            processorMap.put(key, processor);
            log.debug("注册处理器: {} -> {}", key, processor.getProcessorName());
        }
    }

    /**
     * 根据文件扩展名获取处理器
     */
    public Optional<FileProcessor> getProcessor(String fileExtension) {
        if (fileExtension == null || fileExtension.trim().isEmpty()) {
            return Optional.empty();
        }
        String key = fileExtension.toLowerCase().trim();
        FileProcessor processor = processorMap.get(key);
        if (processor != null) {
            log.debug("为文件类型 '{}' 找到处理器: {}", key, processor.getProcessorName());
        } else {
            log.warn("未找到支持文件类型 '{}' 的处理器", key);
        }
        return Optional.ofNullable(processor);
    }

    /**
     * 获取所有支持的文件类型
     */
    public java.util.Set<String> getSupportedFileTypes() {
        return java.util.Collections.unmodifiableSet(processorMap.keySet());
    }
}