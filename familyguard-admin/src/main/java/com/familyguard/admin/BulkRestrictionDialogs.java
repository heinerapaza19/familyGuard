package com.familyguard.admin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Acciones masivas del panel «Mis dispositivos» (Etapa 4): bloquear o retirar sitios web, limitar o retirar
 * aplicaciones y consultar resultados. Toda operación pasa por vista previa y confirmación; el backend valida
 * de nuevo cada equipo y la familia. Las peticiones son asíncronas: la interfaz JavaFX nunca se bloquea.
 */
final class BulkRestrictionDialogs {
    /** Ejecuta una petición y devuelve el resultado en el hilo de JavaFX (con el manejo de sesión del panel). */
    interface AsyncRunner {
        <T> void run(CompletableFuture<T> future, Consumer<T> success, Consumer<String> failure);
    }

    private final AdminApiClient api;
    private final AsyncRunner async;
    private final Supplier<Set<Long>> selection;
    private final Runnable refresh;

    BulkRestrictionDialogs(AdminApiClient api, AsyncRunner async, Supplier<Set<Long>> selection, Runnable refresh) {
        this.api = api; this.async = async; this.selection = selection; this.refresh = refresh;
    }

    // ------------------------------------------------------------------ Sitios web

    void blockSites() { sites("BLOQUEAR_SITIO", "Bloquear sitio web", "bloquear"); }
    void retireSites() { sites("RETIRAR_SITIO", "Retirar sitio web", "retirar el bloqueo de"); }

    private void sites(String type, String title, String verb) {
        List<Long> devices = selected();
        if (devices.isEmpty()) return;
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(title + " en " + devices.size() + (devices.size() == 1 ? " computadora" : " computadoras"));
        ButtonType next = new ButtonType("Continuar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(next, ButtonType.CANCEL);
        TextArea domains = new TextArea();
        domains.setPromptText("tiktok.com\ninstagram.com\nfacebook.com");
        domains.setPrefRowCount(6);
        Label help = new Label("Un dominio por línea (máximo " + BulkRestrictions.MAX_DOMAINS + "). Se bloquean también sus subdominios."
                + " El bloqueo solo figura como aplicado cuando el Agente lo confirma.");
        help.setWrapText(true);
        VBox content = new VBox(10, help, domains);
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().lookupButton(next).disableProperty().bind(domains.textProperty().isEmpty());
        dialog.setResultConverter(button -> button == next ? domains.getText() : null);
        style(dialog);
        Optional<String> text = dialog.showAndWait();
        if (text.isEmpty()) return;
        BulkRestrictions.ParsedDomains parsed = BulkRestrictions.parseDomains(text.get());
        if (!parsed.invalid().isEmpty()) {
            info("Dominios no válidos", "Revise estas entradas (solo se admiten dominios, sin rutas ni IP): "
                    + String.join(", ", parsed.invalid()));
            return;
        }
        if (parsed.valid().isEmpty()) return;
        if (parsed.valid().size() > BulkRestrictions.MAX_DOMAINS) {
            info("Demasiados dominios", "Indique como máximo " + BulkRestrictions.MAX_DOMAINS + " dominios por operación.");
            return;
        }
        List<AdminApiClient.BulkRequest> requests = new ArrayList<>();
        for (String domain : parsed.valid())
            requests.add(new AdminApiClient.BulkRequest(UUID.randomUUID(), type, devices, domain, null, null));
        previewAndRun(requests, verb, parsed.valid());
    }

    // ------------------------------------------------------------------ Aplicaciones

    void limitApplication() { application("LIMITAR_APLICACION", "Limitar aplicación"); }
    void retireApplicationLimit() { application("RETIRAR_LIMITE", "Retirar límite de aplicación"); }

    private void application(String type, String title) {
        List<Long> devices = selected();
        if (devices.isEmpty()) return;
        Set<Long> ids = new LinkedHashSet<>(devices);
        async.run(api.applications(null, null, null), apps -> {
            List<AdminApiClient.ApplicationInfo> references = BulkRestrictions.referenceApplications(apps, ids);
            if (references.isEmpty()) {
                info(title, "Ninguna de las computadoras seleccionadas tiene aplicaciones detectadas todavía. Abra la aplicación"
                        + " una vez en el equipo para que el Agente la registre y vuelva a intentarlo.");
                return;
            }
            Dialog<AdminApiClient.BulkRequest> dialog = new Dialog<>();
            dialog.setTitle(title);
            dialog.setHeaderText(title + " en " + devices.size() + (devices.size() == 1 ? " computadora" : " computadoras"));
            ButtonType next = new ButtonType("Continuar", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(next, ButtonType.CANCEL);
            ComboBox<AdminApiClient.ApplicationInfo> app = new ComboBox<>(FXCollections.observableArrayList(references));
            app.setCellFactory(ignored -> applicationCell());
            app.setButtonCell(applicationCell());
            app.setMaxWidth(Double.MAX_VALUE);
            Label coverage = new Label();
            coverage.setWrapText(true);
            app.valueProperty().addListener((o, a, value) -> {
                if (value == null) { coverage.setText(""); return; }
                String exe = BulkRestrictions.executable(value.executablePath());
                int found = BulkRestrictions.devicesWithExecutable(apps, ids, exe);
                coverage.setText(exe + " está detectado en " + found + " de " + devices.size() + " equipos seleccionados."
                        + (found < devices.size() ? " En los demás no se aplicará hasta que el Agente lo registre." : ""));
            });
            Spinner<Integer> minutes = new Spinner<>(15, 1440, 120, 15);
            minutes.setEditable(true);
            Label minutesText = new Label(BulkRestrictions.duration(120) + " diarios");
            minutes.valueProperty().addListener((o, a, value) -> minutesText.setText(BulkRestrictions.duration(value) + " diarios"));
            VBox content = new VBox(10, new Label("Aplicación detectada"), app, coverage);
            if ("LIMITAR_APLICACION".equals(type))
                content.getChildren().addAll(new Label("Tiempo máximo diario (minutos)"), new HBox(10, minutes, minutesText));
            content.setPadding(new Insets(8));
            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane().lookupButton(next).disableProperty().bind(app.valueProperty().isNull());
            dialog.setResultConverter(button -> button == next ? new AdminApiClient.BulkRequest(UUID.randomUUID(), type,
                    devices, null, app.getValue().id(), "LIMITAR_APLICACION".equals(type) ? minutes.getValue() : null) : null);
            style(dialog);
            dialog.showAndWait().ifPresent(request -> previewAndRun(List.of(request), null, List.of()));
        }, error -> info(title, error));
    }

    private static ListCell<AdminApiClient.ApplicationInfo> applicationCell() {
        return new ListCell<>() {
            @Override protected void updateItem(AdminApiClient.ApplicationInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name() + "  ·  " + BulkRestrictions.executable(item.executablePath()));
            }
        };
    }

    // ------------------------------------------------------------------ Vista previa, confirmación y ejecución

    private void previewAndRun(List<AdminApiClient.BulkRequest> requests, String verb, List<String> targets) {
        List<CompletableFuture<AdminApiClient.BulkPreview>> previews = requests.stream().map(api::bulkPreview).toList();
        CompletableFuture<List<AdminApiClient.BulkPreview>> all = CompletableFuture
                .allOf(previews.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> previews.stream().map(CompletableFuture::join).toList());
        async.run(all, values -> {
            AdminApiClient.BulkPreview first = values.get(0);
            String message = values.size() == 1 ? first.message()
                    : BulkRestrictions.confirmation(verb, targets, first.total(), first.connected());
            List<String> notes = BulkRestrictions.previewNotes(values);
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, ButtonType.OK);
            confirm.setTitle("Confirmar operación");
            confirm.setHeaderText("Confirmar operación");
            if (!notes.isEmpty()) {
                TextArea detail = new TextArea(String.join("\n", notes));
                detail.setEditable(false); detail.setWrapText(true); detail.setPrefRowCount(Math.min(8, notes.size() + 1));
                confirm.getDialogPane().setExpandableContent(new VBox(6, new Label("Detalle por equipo:"), detail));
                confirm.getDialogPane().setExpanded(true);
            }
            style(confirm);
            if (confirm.showAndWait().filter(ButtonType.OK::equals).isPresent()) execute(requests);
        }, error -> info("No se pudo preparar la operación", error));
    }

    /**
     * Ejecuta las operaciones una tras otra. Si la red falla, «Reintentar» reenvía exactamente las mismas
     * solicitudes (mismos identificadores): las ya registradas no se duplican ni vuelven a modificar nada.
     */
    private void execute(List<AdminApiClient.BulkRequest> requests) {
        CompletableFuture<List<AdminApiClient.BulkOperation>> chain = CompletableFuture.completedFuture(new ArrayList<>());
        for (AdminApiClient.BulkRequest request : requests)
            chain = chain.thenCompose(list -> api.bulkExecute(request).thenApply(op -> { list.add(op); return list; }));
        async.run(chain, operations -> { refresh.run(); showResults(operations); }, error -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, error + "\n\nPuede reintentar con seguridad: las operaciones ya"
                    + " registradas no se duplicarán.", ButtonType.CANCEL);
            ButtonType retry = new ButtonType("Reintentar", ButtonBar.ButtonData.OK_DONE);
            alert.getButtonTypes().add(retry);
            alert.setTitle("La operación no se completó");
            alert.setHeaderText("La operación no se completó");
            style(alert);
            if (alert.showAndWait().filter(retry::equals).isPresent()) execute(requests);
            else refresh.run();
        });
    }

    private void showResults(List<AdminApiClient.BulkOperation> operations) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Resultados");
        dialog.setHeaderText(operations.size() == 1 ? operations.get(0).message() : "Operaciones registradas: " + operations.size());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        VBox content = new VBox(12);
        for (AdminApiClient.BulkOperation op : operations) content.getChildren().add(operationView(op));
        Label note = new Label("Los estados se actualizan cuando cada Agente descarga y confirma la política. Consulte"
                + " «Consultar resultados» para ver el avance.");
        note.setWrapText(true);
        content.getChildren().add(note);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(760);
        style(dialog);
        dialog.showAndWait();
    }

    // ------------------------------------------------------------------ Consultar resultados

    void showHistory() {
        async.run(api.bulkOperations(), operations -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("Consultar resultados");
            dialog.setHeaderText("Operaciones masivas recientes");
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            TableView<AdminApiClient.BulkOperation> table = new TableView<>(FXCollections.observableArrayList(operations));
            table.setPlaceholder(new Label("Todavía no hay operaciones masivas"));
            table.getColumns().add(column("Fecha", op -> op.createdAt() == null ? "" : java.time.format.DateTimeFormatter
                    .ofPattern("dd/MM/yyyy HH:mm").format(op.createdAt().atZone(java.time.ZoneId.systemDefault()))));
            table.getColumns().add(column("Acción", op -> BulkRestrictions.typeLabel(op.type())));
            table.getColumns().add(column("Objetivo", AdminApiClient.BulkOperation::target));
            table.getColumns().add(column("Equipos", op -> Integer.toString(op.total())));
            table.getColumns().add(column("Estado", op -> "CANCELADA".equals(op.status()) ? "Cancelada · " + op.message() : op.message()));
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            table.setPrefHeight(220);
            VBox detail = new VBox(8, new Label("Seleccione una operación para ver el estado de cada equipo."));
            Button update = new Button("Actualizar");
            Button cancel = new Button("Cancelar operación");
            cancel.setDisable(true);
            Runnable[] showSelected = new Runnable[1];
            showSelected[0] = () -> {
                AdminApiClient.BulkOperation selected = table.getSelectionModel().getSelectedItem();
                if (selected == null) return;
                async.run(api.bulkOperation(selected.id()), op -> {
                    detail.getChildren().setAll(operationView(op));
                    cancel.setDisable(!BulkRestrictions.cancellable(op));
                }, error -> detail.getChildren().setAll(new Label(error)));
            };
            table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showSelected[0].run());
            update.setOnAction(e -> showSelected[0].run());
            cancel.setOnAction(e -> {
                AdminApiClient.BulkOperation selected = table.getSelectionModel().getSelectedItem();
                if (selected == null) return;
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Se retirará el bloqueo de " + selected.target()
                        + " en los equipos que todavía no lo aplicaron. Los que ya lo aplicaron lo conservan"
                        + " (use «Retirar restricción» para quitarlo).", ButtonType.CANCEL, ButtonType.OK);
                confirm.setHeaderText("Cancelar operación");
                style(confirm);
                if (confirm.showAndWait().filter(ButtonType.OK::equals).isPresent())
                    async.run(api.bulkCancel(selected.id()), op -> {
                        detail.getChildren().setAll(operationView(op));
                        cancel.setDisable(true);
                        refresh.run();
                    }, error -> info("No se pudo cancelar", error));
            });
            HBox actions = new HBox(8, update, cancel);
            VBox content = new VBox(10, table, actions, detail);
            VBox.setVgrow(detail, Priority.ALWAYS);
            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane().setPrefSize(860, 640);
            style(dialog);
            if (!operations.isEmpty()) table.getSelectionModel().selectFirst();
            dialog.showAndWait();
        }, error -> info("Consultar resultados", error));
    }

    private VBox operationView(AdminApiClient.BulkOperation op) {
        Label title = new Label(BulkRestrictions.typeLabel(op.type()) + ": " + op.target()
                + (op.parameters() == null || op.parameters().isBlank() ? "" : " (" + parameters(op.parameters()) + ")"));
        title.getStyleClass().add("detail-value");
        Label message = new Label(op.message());
        message.setWrapText(true);
        TableView<AdminApiClient.BulkDeviceResult> table = new TableView<>(FXCollections.observableArrayList(op.devices()));
        table.getColumns().add(column("Dispositivo", AdminApiClient.BulkDeviceResult::deviceName));
        table.getColumns().add(column("Hijo", AdminApiClient.BulkDeviceResult::childName));
        table.getColumns().add(column("Conexión", r -> r.connected() ? "Conectado" : "Sin conexión"));
        table.getColumns().add(column("Estado", r -> BulkRestrictions.stateLabel(r.state())));
        table.getColumns().add(column("Detalle", r -> r.detail() == null || r.detail().isBlank() ? "—" : r.detail()));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(Math.min(320, 34 + 30 * Math.max(1, op.devices().size())));
        return new VBox(6, title, message, table);
    }

    private static String parameters(String value) {
        if (value.startsWith("minutosDiarios=")) {
            try { return BulkRestrictions.duration(Integer.parseInt(value.substring(15))) + " diarios"; }
            catch (NumberFormatException ignored) { return value; }
        }
        return value;
    }

    // ------------------------------------------------------------------ utilidades

    private List<Long> selected() {
        List<Long> ids = new ArrayList<>(selection.get());
        if (ids.isEmpty()) info("Sin dispositivos seleccionados",
                "Marque una o varias computadoras en «Mis dispositivos» (o use «Seleccionar todos») antes de aplicar una restricción.");
        return ids;
    }

    private static <T> TableColumn<T, String> column(String title, java.util.function.Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }

    private void info(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(title);
        style(alert);
        alert.showAndWait();
    }

    private static void style(Dialog<?> dialog) { ThemeManager.get().style(dialog.getDialogPane()); }
}
