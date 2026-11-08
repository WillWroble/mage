package mage.player.cabt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import mage.cards.Card;

import java.util.List;
import java.util.function.Supplier;

/**
 * Transport-independent handler for the {@code all_card_data} protocol
 * command: {@code {"command": "all_card_data"}} in, {@code {"ok": true,
 * "cards": [...]}} out.
 * <p>
 * The subprocess protocol itself (stdin/stdout line transport, Task 18) is
 * not built yet; when it exists it dispatches command strings to handlers
 * like this one. Unknown or malformed commands fail closed with an
 * {@code {"ok": false, "error": ...}} response rather than being guessed at.
 */
public final class MagicCardDataProtocolCommand {

    public static final String COMMAND_NAME = "all_card_data";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final MagicCardDataExporter exporter;
    private final Supplier<List<Card>> cardSource;

    public MagicCardDataProtocolCommand(MagicCardDataExporter exporter, Supplier<List<Card>> cardSource) {
        if (exporter == null || cardSource == null) {
            throw new IllegalArgumentException("exporter and cardSource must not be null");
        }
        this.exporter = exporter;
        this.cardSource = cardSource;
    }

    /**
     * Handles one raw protocol request line and returns the response JSON.
     */
    public String handle(String requestJson) {
        JsonObject request;
        try {
            JsonElement parsed = JsonParser.parseString(requestJson == null ? "" : requestJson);
            if (!parsed.isJsonObject()) {
                return errorResponse("request must be a JSON object");
            }
            request = parsed.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            return errorResponse("request is not valid JSON");
        }
        JsonElement command = request.get("command");
        if (command == null || !command.isJsonPrimitive()) {
            return errorResponse("request has no command field");
        }
        if (!COMMAND_NAME.equals(command.getAsString())) {
            return errorResponse("unknown command: " + command.getAsString());
        }
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.add("cards", GSON.toJsonTree(exporter.export(cardSource.get())));
        return GSON.toJson(response);
    }

    private static String errorResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        response.addProperty("error", message);
        return GSON.toJson(response);
    }
}
