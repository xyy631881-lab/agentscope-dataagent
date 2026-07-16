package io.agentscope.dataagent.agent.application;

import io.agentscope.dataagent.agent.api.AgentWorkspaceController;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/**
 * Pure, stateless path/name sanitisation and filesystem-tree builders backing
 * {@link WorkspaceFileService}. Extracted so the service reads as file-CRUD orchestration,
 * not string-parsing boilerplate. No instance state, no framework coupling.
*/
final class WorkspaceFileSupport {

    public static String validateRelPath(String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        String trimmed = path.trim().replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        for (String segment : trimmed.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path: " + path);
            }
        }
        return trimmed;
    }




    public static String sanitiseFilename(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing filename");
        }
        String trimmed = name.replace("\\", "/");
        int slash = trimmed.lastIndexOf('/');
        String basename = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        if (basename.isEmpty() || basename.equals(".") || basename.equals("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }
        return basename;
    }

    public static List<AgentWorkspaceController.FileNode> collectChildrenFs(
            AbstractFilesystem fs, RuntimeContext rc, String absPath, int depth) {
        List<AgentWorkspaceController.FileNode> out = new ArrayList<>();
        if (depth <= 0) {
            return out;
        }
        // rc carries the user isolation context (userId) so the sandbox slot resolves to the
        // same namespace the agent execution uses — without it the lookup silently falls back to
        // a fresh, empty sandbox.
        LsResult ls = fs.ls(rc, absPath);
        if (!ls.isSuccess() || ls.entries() == null) {
            return out;
        }
        java.util.LinkedHashMap<String, AgentWorkspaceController.FileNode> bySeg =
                new java.util.LinkedHashMap<>();
        String prefix = "/".equals(absPath) ? "" : trimTrailingSlash(absPath) + "/";
        for (FileInfo fi : ls.entries()) {
            String basename = basenameFromFiPath(fi.path());
            if (basename.isEmpty() || basename.equals(".") || basename.equals("..")) {
                continue;
            }
            String rel =
                    prefix.isEmpty()
                            ? basename
                            : (prefix.startsWith("/")
                                    ? prefix.substring(1) + basename
                                    : prefix + basename);
            if (fi.isDirectory()) {
                String childAbs = "/" + rel;
                List<AgentWorkspaceController.FileNode> children =
                        collectChildrenFs(fs, rc, childAbs, depth - 1);
                bySeg.put(
                        basename,
                        new AgentWorkspaceController.FileNode(
                                basename, rel, "dir", null, children));
            } else {
                if (!bySeg.containsKey(basename)) {
                    bySeg.put(
                            basename,
                            new AgentWorkspaceController.FileNode(
                                    basename, rel, "file", fi.size(), null));
                }
            }
        }
        out.addAll(bySeg.values());
        out.sort(
                Comparator.<AgentWorkspaceController.FileNode, Integer>comparing(
                                n -> "dir".equals(n.type()) ? 0 : 1)
                        .thenComparing(AgentWorkspaceController.FileNode::name));
        return out;
    }

    public static List<AgentWorkspaceController.FileNode> collectChildrenHost(
            Path root, int depth) {
        return collectChildrenHost(root, root, depth);
    }

    private static List<AgentWorkspaceController.FileNode> collectChildrenHost(
            Path base, Path root, int depth) {
        List<AgentWorkspaceController.FileNode> out = new ArrayList<>();
        if (root == null || depth <= 0 || !Files.isDirectory(root)) {
            return out;
        }
        try (var stream = Files.list(root)) {
            stream.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        if (name.isBlank()) return;
                        boolean isDir = Files.isDirectory(path);
                        String rel = base.relativize(path).toString().replace('\\', '/');
                        List<AgentWorkspaceController.FileNode> children =
                                isDir ? collectChildrenHost(base, path, depth - 1) : null;
                        Long size = null;
                        if (!isDir) {
                            try {
                                size = Files.size(path);
                            } catch (IOException ignored) {
                                size = null;
                            }
                        }
                        out.add(new AgentWorkspaceController.FileNode(
                                name, rel, isDir ? "dir" : "file", size, children));
                    });
        } catch (IOException ignored) {
            return List.of();
        }
        out.sort(
                Comparator.<AgentWorkspaceController.FileNode, Integer>comparing(
                                n -> "dir".equals(n.type()) ? 0 : 1)
                        .thenComparing(AgentWorkspaceController.FileNode::name));
        return out;
    }

    public static List<AgentWorkspaceController.FileNode> mergeTrees(
            List<AgentWorkspaceController.FileNode> primary,
            List<AgentWorkspaceController.FileNode> fallback) {
        java.util.LinkedHashMap<String, AgentWorkspaceController.FileNode> merged =
                new java.util.LinkedHashMap<>();
        if (fallback != null) {
            for (AgentWorkspaceController.FileNode node : fallback) {
                merged.put(node.path(), node);
            }
        }
        if (primary != null) {
            for (AgentWorkspaceController.FileNode node : primary) {
                AgentWorkspaceController.FileNode previous = merged.get(node.path());
                if ("dir".equals(node.type()) && previous != null && "dir".equals(previous.type())) {
                    merged.put(
                            node.path(),
                            new AgentWorkspaceController.FileNode(
                                    node.name(),
                                    node.path(),
                                    node.type(),
                                    node.size(),
                                    mergeTrees(node.children(), previous.children())));
                } else {
                    merged.put(node.path(), node);
                }
            }
        }
        List<AgentWorkspaceController.FileNode> out = new ArrayList<>(merged.values());
        out.sort(
                Comparator.<AgentWorkspaceController.FileNode, Integer>comparing(
                                n -> "dir".equals(n.type()) ? 0 : 1)
                        .thenComparing(AgentWorkspaceController.FileNode::name));
        return out;
    }

    public static boolean isDirectoryEntry(
            AbstractFilesystem fs, RuntimeContext rc, String relPath) {
        if (relPath == null || relPath.isEmpty()) {
            return true;
        }
        int slash = relPath.lastIndexOf('/');
        String parent = slash > 0 ? "/" + relPath.substring(0, slash) : "/";
        String base = slash >= 0 ? relPath.substring(slash + 1) : relPath;
        LsResult ls = fs.ls(rc, parent);
        if (!ls.isSuccess() || ls.entries() == null) {
            return false;
        }
        for (FileInfo fi : ls.entries()) {
            if (basenameFromFiPath(fi.path()).equals(base)) {
                return fi.isDirectory();
            }
        }
        return false;
    }




    public static AgentWorkspaceController.FileNode fileNode(String rel, boolean isDir, Long size) {
        int slash = rel.lastIndexOf('/');
        String name = slash >= 0 ? rel.substring(slash + 1) : rel;
        return new AgentWorkspaceController.FileNode(
                name, rel, isDir ? "dir" : "file", isDir ? null : size, null);
    }




    public static String basenameFromFiPath(String fiPath) {
        if (fiPath == null) return "";
        String s = trimTrailingSlash(fiPath);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }




    public static String trimTrailingSlash(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

}
