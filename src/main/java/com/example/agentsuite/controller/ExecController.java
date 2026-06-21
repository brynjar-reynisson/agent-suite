package com.example.agentsuite.controller;

import com.example.agentsuite.config.RootDirectories;
import com.example.agentsuite.filter.UserResolverFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@CrossOrigin(origins = {"http://localhost:5176", "http://127.0.0.1:5176", "https://agent.breynisson.org"})
public class ExecController {

    @PostMapping(value = "/ai/exec", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter exec(
            @RequestParam String command,
            @RequestParam String rootDirectory,
            HttpServletRequest request) {

        validateAccess(rootDirectory, request);

        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<Process> processRef = new AtomicReference<>();

        Runnable killProcess = () -> {
            Process p = processRef.get();
            if (p != null) p.destroyForcibly();
        };
        emitter.onCompletion(killProcess);
        emitter.onTimeout(killProcess);

        Thread.ofVirtual().start(() -> {
            try {
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                List<String> cmd = isWindows
                        ? List.of("cmd", "/c", command)
                        : List.of("sh", "-c", command);

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(new File(rootDirectory));
                pb.redirectErrorStream(true);

                Process process = pb.start();
                processRef.set(process);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        emitter.send(SseEmitter.event().name("output").data(line));
                    }
                }

                boolean finished = process.waitFor(10, TimeUnit.MINUTES);
                if (!finished) {
                    process.destroyForcibly();
                    emitter.send(SseEmitter.event().name("error").data("Command timed out after 10 minutes"));
                } else {
                    emitter.send(SseEmitter.event().name("done").data(String.valueOf(process.exitValue())));
                }
                emitter.complete();

            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(e.getMessage() != null ? e.getMessage() : "Unknown error"));
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        });

        return emitter;
    }

    private void validateAccess(String rootDirectory, HttpServletRequest request) {
        boolean isAdmin = Boolean.TRUE.equals(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN));
        if (!isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (rootDirectory == null || rootDirectory.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Root directory is required");
        if (!RootDirectories.ALLOWED.contains(rootDirectory))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid root directory");
    }
}
