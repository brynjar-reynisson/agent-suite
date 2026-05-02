package com.example.agentsuite.controller;

import com.example.agentsuite.service.DeepSeekService;
import com.example.agentsuite.tools.UnixTools;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
public class AiController {

    private static final Set<String> ALLOWED_ROOT_DIRECTORIES = Set.of(
            "",
            "C:/Users/Lenovo/misc_projects/dragon",
            "C:/Users/Lenovo/misc_projects/gexplorer",
            "C:/Users/Lenovo/IdeaProjects/agent-suite"
    );

    private final DeepSeekService deepSeekService;

    public AiController(DeepSeekService deepSeekService) {
        this.deepSeekService = deepSeekService;
    }

    @RequestMapping(path = "/ai/chat", method = {RequestMethod.GET, RequestMethod.POST})
    public String chat(@RequestParam(defaultValue = "Hello, how are you?") String message,
                       @RequestParam(defaultValue = "") String prompt,
                       @RequestParam(defaultValue = "") String rootDirectory) {
        if (!ALLOWED_ROOT_DIRECTORIES.contains(rootDirectory)) {
            return "Error: Access to the specified root directory is not allowed.";
        }

        if (!rootDirectory.isEmpty()) {
            UnixTools tools = new UnixTools(rootDirectory);
            return deepSeekService.chat(prompt, message, tools);
        }

        return deepSeekService.chat(prompt, message);
    }
}
