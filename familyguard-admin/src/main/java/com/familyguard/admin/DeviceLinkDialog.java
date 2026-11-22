package com.familyguard.admin;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * «Agregar dispositivo»: genera un código temporal para un hijo, muestra su vencimiento y consulta
 * cada 3 segundos si el agente ya lo usó, sin que el padre tenga que pulsar nada.
 */
final class DeviceLinkDialog {
    static final int POLL_SECONDS = 3;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", new Locale("es", "PE"));

    private final AdminApiClient api;
    private final Runnable onLinked;
    private final Label code = new Label("— — — —");
    private final Label expiry = new Label("");
    private final Label status = new Label("Seleccione al hijo y pulse «Generar código».");
    private final Button generate = new Button("Generar código");
    private final Button copy = new Button("Copiar código");
    private Timeline timeline;
    private AdminApiClient.LinkCode current;
    private boolean polling;
    private int ticks;

    private DeviceLinkDialog(AdminApiClient api, Runnable onLinked) { this.api = api; this.onLinked = onLinked; }

    static void show(AdminApiClient api, List<AdminApiClient.ChildProfile> children, Long selectedChildId, Runnable onLinked) {
        new DeviceLinkDialog(api, onLinked).open(children, selectedChildId);
    }

    private void open(List<AdminApiClient.ChildProfile> children, Long selectedChildId) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Agregar dispositivo");
        dialog.setHeaderText("Agregar dispositivo");
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Cerrar", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE));

        ComboBox<AdminApiClient.ChildProfile> child = new ComboBox<>();
        child.getItems().setAll(children.stream().filter(AdminApiClient.ChildProfile::activo).toList());
        child.setConverter(new StringConverter<>() {
            @Override public String toString(AdminApiClient.ChildProfile value) { return value == null ? "" : value.nombre(); }
            @Override public AdminApiClient.ChildProfile fromString(String value) { return null; }
        });
        child.setPromptText("Seleccione al hijo");
        child.getItems().stream().filter(c -> selectedChildId != null && c.id() == selectedChildId).findFirst()
                .ifPresent(child::setValue);
        if (child.getValue() == null && child.getItems().size() == 1) child.setValue(child.getItems().get(0));
        child.setOnAction(event -> resetForNewChild());

        code.getStyleClass().add("link-code");
        code.setStyle("-fx-font-size: 34px; -fx-font-weight: bold; -fx-font-family: 'Consolas', monospace;");
        status.setWrapText(true);
        copy.setDisable(true);
        copy.setOnAction(event -> {
            if (current == null) return;
            ClipboardContent content = new ClipboardContent();
            content.putString(current.code());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            status.setText("Código copiado. Escríbalo en FamilyGuard Agente de la computadora del hijo.");
        });
        generate.setOnAction(event -> {
            AdminApiClient.ChildProfile selected = child.getValue();
            if (selected == null) { status.setText("Seleccione al hijo al que pertenecerá la computadora."); return; }
            generate(selected.id());
        });

        Label steps = new Label("""
                1. Instale FamilyGuard Agente en la computadora del hijo.
                2. Ábralo y escriba este código.
                3. Pulse «Vincular dispositivo». Esta ventana lo confirmará automáticamente.""");
        steps.setWrapText(true);
        VBox body = new VBox(12,
                new Label("Hijo"), child,
                new HBox(10, generate, copy),
                code, expiry, status, steps);
        body.setPadding(new Insets(16));
        body.setPrefWidth(460);
        body.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(body);
        ThemeManager.get().style(dialog.getDialogPane());
        dialog.setOnHidden(event -> stop());
        String withoutChildren = FamilyTexts.linkWithoutChildren(children);
        if (withoutChildren != null) {
            // Solo una indicación: el padre decide cuándo agregar hijos; no se abre ningún formulario.
            generate.setDisable(true);
            status.setText(withoutChildren);
        }
        dialog.showAndWait();
    }

    private void generate(long childId) {
        stop();
        generate.setDisable(true);
        status.setText("Generando código…");
        api.createDeviceLinkCode(childId).whenComplete((linkCode, error) -> Platform.runLater(() -> {
            generate.setDisable(false);
            if (error != null) { status.setText(message(error)); return; }
            current = linkCode;
            code.setText(format(linkCode.code()));
            copy.setDisable(false);
            generate.setText("Generar otro código");
            status.setText("Esperando que el agente se vincule… (al generar otro código, este quedará anulado)");
            updateExpiry();
            startPolling();
        }));
    }

    private void startPolling() {
        ticks = 0;
        timeline = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), event -> {
            updateExpiry();
            if (++ticks % POLL_SECONDS == 0) poll();
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    private void poll() {
        AdminApiClient.LinkCode code = current;
        if (code == null || code.id() == null || polling) return;
        polling = true;
        api.linkCodeStatus(code.id()).whenComplete((state, error) -> Platform.runLater(() -> {
            polling = false;
            if (code != current) return;            // se generó otro código mientras tanto
            if (error != null) { status.setText("Sin respuesta del servidor; se reintentará. " + message(error)); return; }
            status.setText(statusMessage(state.state(), state.deviceName()));
            if (!"PENDIENTE".equals(state.state())) {
                stop();
                if ("VINCULADO".equals(state.state())) {
                    expiry.setText("");
                    copy.setDisable(true);
                    if (onLinked != null) onLinked.run();
                }
            }
        }));
    }

    private void updateExpiry() {
        if (current == null || current.expiresAt() == null) return;
        Duration left = Duration.between(Instant.now(), current.expiresAt());
        expiry.setText(left.isNegative() || left.isZero()
                ? "Código vencido"
                : "Vence a las " + TIME.format(current.expiresAt().atZone(ZoneId.systemDefault()))
                    + " (quedan " + remaining(left) + ")");
    }

    private void resetForNewChild() {
        stop();
        current = null;
        code.setText("— — — —");
        expiry.setText("");
        copy.setDisable(true);
        generate.setText("Generar código");
        status.setText("Pulse «Generar código» para este hijo.");
    }

    private void stop() { if (timeline != null) { timeline.stop(); timeline = null; } }

    /** Mensaje visible para cada estado del código. */
    static String statusMessage(String state, String deviceName) {
        return switch (state == null ? "" : state) {
            case "VINCULADO" -> "✔ Dispositivo vinculado correctamente"
                    + (deviceName == null || deviceName.isBlank() ? "." : ": " + deviceName + ".");
            case "VENCIDO" -> "El código venció sin usarse. Pulse «Generar otro código».";
            case "REEMPLAZADO" -> "Este código fue reemplazado por uno más reciente.";
            default -> "Esperando que el agente se vincule…";
        };
    }

    /** Muestra el código en dos bloques legibles; el agente acepta espacios y guiones. */
    static String format(String code) {
        return code == null || code.length() != 8 ? String.valueOf(code) : code.substring(0, 4) + " " + code.substring(4);
    }

    static String remaining(Duration left) {
        long seconds = Math.max(0, left.getSeconds());
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    private static String message(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() == null ? "No se pudo completar la operación." : cause.getMessage();
    }
}
