package com.familyguard.agent;

import java.util.function.UnaryOperator;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Controlador visual de la pantalla de vinculación; la vinculación real la hace {@link AgentApplication}. */
public final class AgentLinkController {
    @FXML TextField codeField;
    @FXML Button linkButton;
    @FXML HBox progressBox;
    @FXML Label progressLabel;
    @FXML HBox errorBox;
    @FXML Label errorLabel;
    @FXML Hyperlink configureLink;
    @FXML VBox connectionBox;
    @FXML TextField serverField;
    @FXML Label serverHint;

    @FXML private void initialize() {
        // Mayúsculas y como máximo 8 letras o números más un separador opcional (espacio o guion).
        UnaryOperator<TextFormatter.Change> filter = change -> {
            String text = change.getControlNewText().toUpperCase(java.util.Locale.ROOT);
            if (!text.matches("[A-Z0-9 \\-]{0,9}")) return null;
            change.setText(change.getText().toUpperCase(java.util.Locale.ROOT));
            return change;
        };
        codeField.setTextFormatter(new TextFormatter<>(filter));
        codeField.textProperty().addListener((obs, old, value) -> hideError());
    }

    @FXML private void toggleConnection() { showConnection(!connectionBox.isVisible()); }

    void showConnection(boolean visible) {
        connectionBox.setVisible(visible); connectionBox.setManaged(visible);
        configureLink.setText(visible ? "Ocultar configuración de conexión" : "Configurar conexión");
    }

    void busy(boolean busy, String text) {
        progressBox.setVisible(busy); progressBox.setManaged(busy); progressLabel.setText(text == null ? "" : text);
        linkButton.setDisable(busy); codeField.setDisable(busy); serverField.setDisable(busy);
        if (busy) hideError();
    }

    void error(String text) {
        errorLabel.setText(text);
        errorBox.setVisible(true); errorBox.setManaged(true);
    }

    void hideError() { errorBox.setVisible(false); errorBox.setManaged(false); }
}
