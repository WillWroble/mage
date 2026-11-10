package org.mage.spellbench;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A subprocess speaking newline-delimited JSON on stdin and stdout, one response per request: the kernel's protocol
 * engine and the Archon agent. Its stderr goes to a file.
 */
final class NdjsonProcess implements AutoCloseable {

    private final Process process;
    private final Writer in;
    private final BufferedReader out;
    private int requests;

    NdjsonProcess(List<String> command, File stderr) {
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.appendTo(stderr)).start();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start " + command, e);
        }
        in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /** Sends {@code request} with the protocol envelope and returns the response. */
    JsonObject request(String protocol, String type, JsonObject request) {
        JsonObject message = request.deepCopy();
        message.addProperty("protocol", protocol);
        message.addProperty("request_type", type);
        message.addProperty("request_id", "r-" + requests++);
        try {
            in.write(message + "\n");
            in.flush();
            String line = out.readLine();
            if (line == null) {
                throw new IllegalStateException(type + ": the process ended");
            }
            return JsonParser.parseString(line).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(type + ": the process failed", e);
        }
    }

    @Override
    public void close() {
        process.destroy();
    }
}
