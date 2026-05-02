package com.example.agentsuite.controller;

import com.example.agentsuite.service.ChatService;
import com.example.agentsuite.service.ModelRegistry;
import com.example.agentsuite.tools.UnixTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@CrossOrigin(origins = {"http://localhost:5176", "http://127.0.0.1:5176", "https://agent.breynisson.org"})
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private static final Set<String> ALLOWED_ROOT_DIRECTORIES = Set.of(
            "",
            "C:/Users/Lenovo/misc_projects/dragon",
            "C:/Users/Lenovo/misc_projects/gexplorer",
            "C:/Users/Lenovo/IdeaProjects/agent-suite"
    );

    private final ModelRegistry modelRegistry;

    public AiController(ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

    @GetMapping("/ai/config/directories")
    public Set<String> getAllowedDirectories() {
        return ALLOWED_ROOT_DIRECTORIES;
    }

    @RequestMapping(path = "/ai/chat", method = {RequestMethod.GET, RequestMethod.POST})
    public String chat(@RequestParam(defaultValue = "Hello, how are you?") String message,
                       @RequestParam(defaultValue = "") String prompt,
                       @RequestParam(defaultValue = "") String rootDirectory,
                       @RequestParam(defaultValue = "deepseek-v4-pro") String model) {

        ChatService service = modelRegistry.get(model);
        if (service == null) return "Error: Unknown model: " + model;

        if (!ALLOWED_ROOT_DIRECTORIES.contains(rootDirectory))
            return "Error: Access to the specified root directory is not allowed.";

        log.info("Chat request - model: {}, prompt: {}, message: {}, rootDirectory: {}", model, prompt, message, rootDirectory);
        if (!rootDirectory.isEmpty())
            return service.chat(prompt, message, new UnixTools(rootDirectory));

        return service.chat(prompt, message);
    }
}
