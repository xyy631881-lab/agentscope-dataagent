package io.agentscope.dataagent.agent.application;

import io.agentscope.dataagent.agent.api.AgentWorkspaceController;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
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
            AbstractFilesystem fs, String absPath, int depth) {
        List<AgentWorkspaceController.FileNode> out = new ArrayList<>();
        if (depth <= 0) {
            return out;
        }
        LsResult ls = fs.ls(RuntimeContext.empty(), absPath);
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
                        collectChildrenFs(fs, childAbs, depth - 1);
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
