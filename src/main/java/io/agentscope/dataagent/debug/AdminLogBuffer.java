package io.agentscope.dataagent.debug;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Small bounded log fan-out used by the admin debug page. */
@Component
public class AdminLogBuffer {
    private static final int MAX_LINES = 1000;
    private final ConcurrentLinkedDeque<String> lines = new ConcurrentLinkedDeque<>();
    private final CopyOnWriteArrayList<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private AppenderBase<ILoggingEvent> appender;

    @PostConstruct
    void attach() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                String line = event.getFormattedMessage();
                if (line == null || line.isBlank()) return;
                String rendered = event.getLevel() + " [" + event.getThreadName() + "] " + line;
                lines.addLast(rendered);
                while (lines.size() > MAX_LINES) lines.pollFirst();
                for (SseEmitter subscriber : subscribers) {
                    try {
                        subscriber.send(SseEmitter.event().name("log").data(rendered));
                    } catch (IOException e) {
                        subscribers.remove(subscriber);
                        subscriber.complete();
                    }
                }
            }
        };
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    @PreDestroy
    void detach() {
        if (appender != null) {
            ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
            appender.stop();
        }
    }

    public boolean isAttached() {
        return appender != null && appender.isStarted();
    }

    public List<String> recent() {
        return new ArrayList<>(lines);
    }

    public SseEmitter openStream() {
        SseEmitter emitter = new SseEmitter(0L);
        subscribers.add(emitter);
        try {
            for (String line : lines) emitter.send(SseEmitter.event().name("log").data(line));
        } catch (IOException e) {
            subscribers.remove(emitter);
            emitter.completeWithError(e);
        }
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(error -> subscribers.remove(emitter));
        return emitter;
    }
}
