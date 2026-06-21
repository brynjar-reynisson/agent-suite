package com.example.agentsuite.controller;

import com.example.agentsuite.config.RootDirectories;
import com.example.agentsuite.filter.UserResolverFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@RestController
@CrossOrigin(origins = {"http://localhost:5176", "http://127.0.0.1:5176", "https://agent.breynisson.org"})
public class FileController {

    @GetMapping(value = "/ai/files", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> readFile(
            @RequestParam String path,
            @RequestParam String rootDirectory,
            HttpServletRequest request) {
        validateAccess(path, rootDirectory, request);
        Path resolved = resolveSafe(path, rootDirectory);
        try {
            return ResponseEntity.ok(Files.readString(resolved, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping(value = "/ai/files", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> writeFile(
            @RequestParam String path,
            @RequestParam String rootDirectory,
            @RequestBody String content,
            HttpServletRequest request) {
        validateAccess(path, rootDirectory, request);
        Path resolved = resolveSafe(path, rootDirectory);
        Path temp;
        try {
            temp = Files.createTempFile(resolved.getParent(), ".tmp-edit-", null);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot create temp file");
        }
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, resolved, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, resolved, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Write failed");
        }
        return ResponseEntity.noContent().build();
    }

    private void validateAccess(String path, String rootDirectory, HttpServletRequest request) {
        boolean isAdmin = Boolean.TRUE.equals(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN));
        if (!isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (!RootDirectories.ALLOWED.contains(rootDirectory) || rootDirectory.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid root directory");
        if (isAbsolutePath(path) || containsTraversal(path))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
    }

    private boolean isAbsolutePath(String path) {
        return path.startsWith("/") || path.startsWith("\\")
                || (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':');
    }

    private boolean containsTraversal(String path) {
        for (String segment : path.replace('\\', '/').split("/", -1)) {
            if ("..".equals(segment)) return true;
        }
        return false;
    }

    private Path resolveSafe(String relPath, String rootDirectory) {
        try {
            Path root = Path.of(rootDirectory).toRealPath();
            Path resolved = root.resolve(relPath).normalize();
            if (!resolved.startsWith(root))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Path escapes root");
            return resolved;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot resolve root directory");
        }
    }
}
