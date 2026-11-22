package com.familyguard.admin;

import java.time.LocalDate;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Screen;
import javafx.util.Duration;

/** Cliente de escritorio para registro, autenticación y administración familiar. */
public class AdminApplication extends Application {
    private final AdminApiConfiguration apiConfiguration = new AdminApiConfiguration();
    private AdminApiClient api;
    private final StackPane root = new StackPane();

    @Override
    public void stop() { AdminTray.shutdown(); }

    @Override
    public void start(Stage stage) {
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("es-PE"));
        stage.setTitle("FamilyGuard · Administración familiar");
        var bounds = Screen.getPrimary().getVisualBounds();
        double width = Math.min(1500, Math.max(1020, bounds.getWidth() - 48));
        double height = Math.min(880, Math.max(680, bounds.getHeight() - 48));
        Scene scene = new Scene(root, width, height);
        ThemeManager.get().apply(scene);
        stage.setMinWidth(1020);
        stage.setMinHeight(680);
        stage.setScene(scene);
        apiConfiguration.load().ifPresentOrElse(uri -> {
            api = new AdminApiClient(uri); showLogin();
        }, () -> {
            api = new AdminApiClient(URI.create("http://localhost:8080")); showInitialSetup();
        });
        stage.show();
        stage.centerOnScreen();
    }

    private void showInitialSetup() {
        Label message = messageLabel();
        ComboBox<String> mode = new ComboBox<>(FXCollections.observableArrayList("Local", "Remoto HTTPS"));
        mode.getSelectionModel().selectFirst();
        TextField url = textField("http://localhost:8080"); url.setText("http://localhost:8080");
        mode.valueProperty().addListener((observable, previous, selected) -> url.setText(
                "Local".equals(selected) ? "http://localhost:8080" : "https://"));
        Button connect = primaryButton("Probar y guardar servidor"); withIcon(connect, "server");
        connect.setMaxWidth(Double.MAX_VALUE);
        connect.setOnAction(event -> {
            message.setText("");
            URI uri;
            try { uri = AdminApiConfiguration.validate(url.getText()); }
            catch (IllegalArgumentException invalid) { message.setText(invalid.getMessage()); return; }
            setBusy(connect, true); message.setText("Comprobando conexión segura…");
            AdminApiConfiguration.probe(uri).whenComplete((ignored, error) -> Platform.runLater(() -> {
                setBusy(connect, false);
                if (error != null) { message.setText("No se pudo conectar: " + errorMessage(error)); return; }
                apiConfiguration.save(uri.toString()); api = new AdminApiClient(uri); showLogin();
            }));
        });
        VBox setup = card("Configurar FamilyGuard", "Elija dónde está ejecutándose la API. No introduzca credenciales de MySQL.",
                labeled("Tipo de conexión", mode), labeled("Dirección de la API", inputWithIcon("server", url)),
                infoLabel("Local permite HTTP únicamente en esta computadora. Un servidor remoto requiere HTTPS y certificado válido."),
                connect, message);
        showAuthPage(setup, "Protección preparada\npara su entorno.",
                "FamilyGuard se comunica exclusivamente con la API. MySQL nunca se expone al escritorio.");
    }

    private Label infoLabel(String value) { Label label = new Label(value); label.setWrapText(true); label.getStyleClass().add("muted"); return label; }

    private void showLogin() {
        Label message = messageLabel();
        TextField email = textField("Usuario");
        PasswordFields password = passwordFields("Contraseña");
        Button login = primaryButton("Iniciar sesión");
        Button register = secondaryButton("Crear una cuenta familiar");
        withIcon(login, "login"); withIcon(register, "add-user");
        login.setMaxWidth(Double.MAX_VALUE); register.setMaxWidth(Double.MAX_VALUE);
        login.setOnAction(event -> {
            message.setText("");
            if (email.getText().isBlank() || password.value().isBlank()) {
                message.setText("Ingrese su usuario y contraseña.");
                return;
            }
            setBusy(login, true);
            complete(api.login(email.getText().strip(), password.value()), this::showDashboard, error -> {
                setBusy(login, false);
                message.setText(error);
            });
        });
        register.setOnAction(event -> showRegistration());
        VBox card = card("Iniciar sesión", "Accede a tu cuenta de FamilyGuard",
                labeled("Usuario", inputWithIcon("profile", email)),
                labeled("Contraseña", inputWithIcon("lock", password.container())),
                login, message, authDivider(), register);
        showAuthPage(card, "FamilyGuard",
                "Control parental para un uso seguro y productivo de la computadora.");
        Platform.runLater(email::requestFocus);
    }

    private void showRegistration() {
        Label message = messageLabel();
        TextField name = textField("Nombres y apellidos");
        TextField family = textField("Nombre de la familia");
        TextField email = textField("Por ejemplo: heiner.apaza");
        PasswordFields password = passwordFields("Mínimo 12 caracteres");
        PasswordFields confirmation = passwordFields("Repita la contraseña");
        Button create = primaryButton("Crear cuenta");
        Button back = linkButton("Volver al inicio de sesión");
        withIcon(create, "add-user");
        create.setOnAction(event -> {
            message.setText("");
            if (name.getText().isBlank() || family.getText().isBlank() || email.getText().isBlank()) {
                message.setText("Complete todos los datos obligatorios.");
                return;
            }
            if (!UsernameRule.valid(email.getText())) { message.setText(UsernameRule.MESSAGE); return; }
            setBusy(create, true);
            complete(api.register(name.getText(), email.getText().strip(), password.value(), confirmation.value(),
                    family.getText()), profile -> {
                setBusy(create, false);
                info("Cuenta creada", "La familia quedó registrada. Ya puede iniciar sesión.");
                showLogin();
            }, error -> {
                setBusy(create, false);
                message.setText(error);
            });
        });
        back.setOnAction(event -> showLogin());
        create.setMaxWidth(Double.MAX_VALUE);
        VBox card = card("Crear cuenta familiar", "Configure el espacio privado desde el que protegerá a su familia.",
                labeled("Nombres y apellidos", inputWithIcon("profile", name)),
                labeled("Nombre de la familia", inputWithIcon("children", family)),
                labeled("Usuario", inputWithIcon("profile", email)),
                labeled("Contraseña", inputWithIcon("lock", password.container())),
                labeled("Confirmar contraseña", inputWithIcon("shield", confirmation.container())),
                smallCaption("SEGURIDAD · USE 12 CARACTERES O MÁS"), create, message,
                authFooter("¿Ya tiene una cuenta?", back));
        card.getStyleClass().add("registration-card");
        ScrollPane scroll = new ScrollPane(card);
        scroll.setFitToWidth(true);
        scroll.setMinHeight(0); scroll.setPrefHeight(660); scroll.setMaxHeight(680);
        scroll.getStyleClass().add("auth-scroll");
        showAuthPage(scroll, "Un espacio seguro\npara toda su familia.",
                "Sus credenciales viajan al servidor configurado y nunca quedan guardadas en esta aplicación.");
    }

    private void showDashboard(AdminApiClient.Profile profile) {
        setView(new DashboardWorkspace(api, profile, this::showLogin, this::changeApi));
    }

    private void changeApi(String value) {
        URI uri = apiConfiguration.save(value);
        api = new AdminApiClient(uri);
        showLogin();
    }

    @SuppressWarnings("unused")
    private void showDashboardLegacy(AdminApiClient.Profile profile) {
        TableView<AdminApiClient.ChildProfile> table = childrenTable();
        Label status = messageLabel();
        Label count = new Label("Cargando perfiles…");
        count.getStyleClass().add("muted");
        Button add = primaryButton("+ Nuevo perfil");
        Button edit = secondaryButton("Editar");
        Button deactivate = dangerButton("Desactivar");
        Button refresh = secondaryButton("Actualizar");
        Button logout = secondaryButton("Cerrar sesión");
        Runnable reload = () -> complete(api.children(), children -> {
            table.setItems(FXCollections.observableArrayList(children));
            count.setText(children.size() + (children.size() == 1 ? " perfil activo" : " perfiles activos"));
            status.setText("");
        }, status::setText);
        add.setOnAction(event -> childDialog(null).ifPresent(input -> complete(
                api.createChild(input.name(), input.birthDate(), input.profileName()), ignored -> reload.run(),
                status::setText)));
        edit.setOnAction(event -> {
            AdminApiClient.ChildProfile selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) { status.setText("Seleccione un perfil para editar."); return; }
            childDialog(selected).ifPresent(input -> complete(
                    api.updateChild(selected.id(), input.name(), input.birthDate(), input.profileName()),
                    ignored -> reload.run(), status::setText));
        });
        deactivate.setOnAction(event -> {
            AdminApiClient.ChildProfile selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) { status.setText("Seleccione un perfil para desactivar."); return; }
            if (confirm("Desactivar perfil", "El perfil de " + selected.nombre() + " dejará de estar activo."))
                complete(api.deactivateChild(selected.id()), ignored -> reload.run(), status::setText);
        });
        refresh.setOnAction(event -> reload.run());
        logout.setOnAction(event -> {
            logout.setDisable(true);
            complete(api.logout(), ignored -> showLogin(), error -> showLogin());
        });

        Label logo = new Label("FG");
        logo.getStyleClass().add("logo-small");
        Label product = new Label("FamilyGuard");
        product.getStyleClass().add("product-name");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, logo, product, spacer, new Label(profile.nombre()), logout);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("topbar");
        VBox familyCard = new VBox(5, smallCaption("FAMILIA"), new Label(profile.familiaNombre()),
                new Label(profile.email()), new Label("Rol: " + profile.rol()));
        familyCard.getStyleClass().add("family-card");
        VBox sidebar = new VBox(22, familyCard, new Label("Administración"), navLabel("Perfiles de hijos"));
        sidebar.getStyleClass().add("sidebar");
        Label title = new Label("Perfiles de hijos");
        title.getStyleClass().add("page-title");
        Region actionSpacer = new Region();
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);
        HBox actions = new HBox(10, count, actionSpacer, refresh, edit, deactivate, add);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(16, title, new Label("Administre únicamente los perfiles de su familia."),
                actions, table, status);
        VBox.setVgrow(table, Priority.ALWAYS);
        content.getStyleClass().add("dashboard-content");
        BorderPane layout = new BorderPane(content, header, null, null, sidebar);
        setView(layout);
        reload.run();
    }

    private TableView<AdminApiClient.ChildProfile> childrenTable() {
        TableView<AdminApiClient.ChildProfile> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Aún no hay perfiles activos"));
        TableColumn<AdminApiClient.ChildProfile, String> name = column("Nombre", value -> value.nombre());
        TableColumn<AdminApiClient.ChildProfile, String> profile = column("Nombre de perfil", value -> value.nombrePerfil());
        TableColumn<AdminApiClient.ChildProfile, String> birth = column("Fecha de nacimiento", value ->
                value.fechaNacimiento() == null ? "No indicada" : value.fechaNacimiento().toString());
        TableColumn<AdminApiClient.ChildProfile, String> state = column("Estado", value ->
                value.activo() ? "Activo" : "Inactivo");
        table.getColumns().addAll(name, profile, birth, state);
        return table;
    }

    private TableColumn<AdminApiClient.ChildProfile, String> column(String title,
            java.util.function.Function<AdminApiClient.ChildProfile, String> value) {
        TableColumn<AdminApiClient.ChildProfile, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }

    private Optional<ChildInput> childDialog(AdminApiClient.ChildProfile current) {
        Dialog<ChildInput> dialog = new Dialog<>();
        dialog.setTitle(current == null ? "Nuevo perfil" : "Editar perfil");
        ButtonType save = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        TextField name = textField("Nombre completo");
        TextField profile = textField("Nombre visible (opcional)");
        DatePicker birthDate = new DatePicker();
        if (current != null) {
            name.setText(current.nombre());
            profile.setText(current.nombrePerfil());
            birthDate.setValue(current.fechaNacimiento());
        }
        GridPane form = new GridPane();
        form.setHgap(12); form.setVgap(12);
        form.addRow(0, new Label("Nombre *"), name);
        form.addRow(1, new Label("Fecha de nacimiento"), birthDate);
        form.addRow(2, new Label("Nombre de perfil"), profile);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().lookupButton(save).disableProperty().bind(name.textProperty().isEmpty());
        dialog.setResultConverter(button -> button == save
                ? new ChildInput(name.getText().strip(), birthDate.getValue(), profile.getText().strip()) : null);
        ThemeManager.get().style(dialog.getDialogPane());
        return dialog.showAndWait();
    }

    private void showAuthPage(Node card, String headline, String description) {
        Label mark = new Label("FG"); mark.getStyleClass().add("brand-mark");
        Label brand = new Label("FamilyGuard"); brand.getStyleClass().add("brand");
        Label brandSubtitle = new Label("PROTECCIÓN FAMILIAR INTELIGENTE"); brandSubtitle.getStyleClass().add("brand-subtitle");
        Region identitySpacer = new Region(); HBox.setHgrow(identitySpacer, Priority.ALWAYS);
        Button themeToggle = new Button(); themeToggle.getStyleClass().add("theme-toggle");
        Runnable updateThemeLabel = () -> themeToggle.setText(
                ThemeManager.get().current() == ThemeManager.Theme.LIGHT ? "☀  Claro" : "☾  Oscuro");
        updateThemeLabel.run();
        themeToggle.setOnAction(event -> ThemeManager.get().setTheme(
                ThemeManager.get().current() == ThemeManager.Theme.LIGHT
                        ? ThemeManager.Theme.DARK : ThemeManager.Theme.LIGHT));
        ThemeManager.get().themeProperty().addListener((observable, oldTheme, newTheme) -> updateThemeLabel.run());
        HBox identity = new HBox(13, mark, new VBox(2, brand, brandSubtitle), identitySpacer, themeToggle);
        identity.setAlignment(Pos.CENTER_LEFT); identity.setMaxWidth(Double.MAX_VALUE);
        Label shieldTitle = new Label("Family"); shieldTitle.getStyleClass().add("hero-title");
        Label accentTitle = new Label("Guard"); accentTitle.getStyleClass().addAll("hero-title", "hero-title-accent");
        HBox productTitle = new HBox(0, shieldTitle, accentTitle); productTitle.setAlignment(Pos.CENTER);
        Label text = new Label(description); text.setWrapText(true); text.setMaxWidth(370);
        text.setMinHeight(Region.USE_PREF_SIZE); text.getStyleClass().add("hero-copy");
        HBox features = new HBox(10, heroFeature("apps", "Control de", "aplicaciones"),
                heroFeature("web", "Bloqueo de", "sitios web"),
                heroFeature("clock", "Horarios y", "tiempos de uso"),
                heroFeature("reports", "Reportes y", "alertas"));
        features.setAlignment(Pos.CENTER);
        VBox hero = new VBox(12, identity, heroVisual(), productTitle, text, features,
                smallCaption("CONEXIÓN · " + api.baseUrl()));
        hero.setAlignment(Pos.CENTER); hero.getStyleClass().add("hero");
        Region orbTop = new Region(), orbBottom = new Region();
        orbTop.getStyleClass().addAll("form-orb", "form-orb-top");
        orbBottom.getStyleClass().addAll("form-orb", "form-orb-bottom");
        StackPane.setAlignment(orbTop, Pos.TOP_RIGHT); StackPane.setAlignment(orbBottom, Pos.BOTTOM_LEFT);
        StackPane formArea = new StackPane(orbTop, orbBottom, card); formArea.getStyleClass().add("form-area");
        BorderPane shell = new BorderPane(formArea, null, null, null, hero); shell.getStyleClass().add("auth-shell");
        setView(shell);
    }

    private Node heroVisual() {
        FamilyIllustration visual = new FamilyIllustration();
        visual.getStyleClass().add("family-illustration"); return visual;
    }

    private VBox orbitCard(String title, String subtitle) {
        Label heading = new Label(title); heading.getStyleClass().add("orbit-icon");
        Label copy = new Label(subtitle); copy.getStyleClass().add("subtle");
        VBox card = new VBox(2, heading, copy); card.getStyleClass().add("orbit-card"); return card;
    }

    private HBox benefit(String icon, String text) {
        Label iconLabel = new Label(icon); iconLabel.getStyleClass().add("benefit-icon");
        Label copy = new Label(text); copy.getStyleClass().add("muted");
        HBox row = new HBox(10, iconLabel, copy); row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("benefit-row"); return row;
    }

    private VBox card(String title, String subtitle, Node... nodes) {
        Label eyebrow = smallCaption("ACCESO SEGURO");
        Label logo = new Label("FG"); logo.getStyleClass().add("auth-logo");
        Label heading = new Label(title); heading.getStyleClass().add("card-title");
        Label copy = new Label(subtitle); copy.setWrapText(true); copy.getStyleClass().add("muted");
        VBox header = new VBox(8, logo, eyebrow, heading, copy); header.setAlignment(Pos.CENTER);
        header.getStyleClass().add("auth-card-header");
        VBox card = new VBox(15, header); card.getChildren().addAll(nodes);
        card.getStyleClass().add("auth-card"); card.setMaxWidth(470);
        return card;
    }

    private HBox authFooter(String text, Button action) {
        Label copy = new Label(text); copy.getStyleClass().add("muted");
        HBox footer = new HBox(8, copy, action); footer.setAlignment(Pos.CENTER);
        footer.getStyleClass().add("auth-footer"); return footer;
    }

    private HBox authDivider() {
        Region left = new Region(), right = new Region();
        left.getStyleClass().add("divider-line"); right.getStyleClass().add("divider-line");
        HBox.setHgrow(left, Priority.ALWAYS); HBox.setHgrow(right, Priority.ALWAYS);
        HBox divider = new HBox(12, left, new Label("o"), right); divider.setAlignment(Pos.CENTER);
        return divider;
    }

    private StackPane inputWithIcon(String icon, Node input) {
        Node vector = VectorIcon.of(icon, 19); vector.getStyleClass().add("input-icon");
        StackPane.setAlignment(vector, Pos.CENTER_LEFT);
        StackPane.setMargin(vector, new javafx.geometry.Insets(0, 0, 0, 13));
        StackPane shell = new StackPane(input, vector); shell.getStyleClass().add("input-shell");
        if (input instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
        return shell;
    }

    private VBox heroFeature(String icon, String first, String second) {
        Node vector = VectorIcon.of(icon, 29); vector.getStyleClass().add("feature-vector");
        Label line1 = new Label(first), line2 = new Label(second);
        line1.getStyleClass().add("feature-copy"); line2.getStyleClass().add("feature-copy");
        VBox card = new VBox(5, vector, line1, line2); card.setAlignment(Pos.CENTER);
        card.getStyleClass().addAll("hero-feature-card", "feature-" + icon);
        HBox.setHgrow(card, Priority.ALWAYS); return card;
    }

    private VBox labeled(String label, Node field) {
        VBox box = new VBox(6, new Label(label), field); box.getStyleClass().add("field-group"); return box;
    }

    private PasswordFields passwordFields(String prompt) {
        PasswordField hidden = new PasswordField(); hidden.setPromptText(prompt);
        TextField visible = new TextField(); visible.setPromptText(prompt);
        visible.textProperty().bindBidirectional(hidden.textProperty());
        visible.setVisible(false); visible.setManaged(false);
        ToggleButton show = new ToggleButton(); show.getStyleClass().add("password-toggle");
        show.setGraphic(VectorIcon.of("eye", 18)); show.setAccessibleText("Mostrar u ocultar contraseña");
        show.selectedProperty().addListener((observable, oldValue, selected) -> {
            visible.setVisible(selected); visible.setManaged(selected);
            hidden.setVisible(!selected); hidden.setManaged(!selected);
        });
        StackPane fields = new StackPane(hidden, visible);
        HBox.setHgrow(fields, Priority.ALWAYS);
        HBox controls = new HBox(8, fields, show); controls.setAlignment(Pos.CENTER_LEFT);
        return new PasswordFields(hidden, controls);
    }

    private TextField textField(String prompt) { TextField field = new TextField(); field.setPromptText(prompt); return field; }
    private Button primaryButton(String text) { return styledButton(text, "primary-button"); }
    private Button secondaryButton(String text) { return styledButton(text, "secondary-button"); }
    private Button dangerButton(String text) { return styledButton(text, "danger-button"); }
    private Button linkButton(String text) { return styledButton(text, "link-button"); }
    private Button styledButton(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private void withIcon(Button button, String icon) {
        Node graphic = VectorIcon.of(icon, 18); graphic.getStyleClass().add("button-icon");
        button.setGraphic(graphic); button.getProperties().put("familyguard.icon", graphic);
    }
    private Label messageLabel() { Label label = new Label(); label.setWrapText(true); label.getStyleClass().add("message"); return label; }
    private Label smallCaption(String text) { Label label = new Label(text); label.getStyleClass().add("caption"); return label; }
    private Label navLabel(String text) { Label label = new Label(text); label.getStyleClass().add("nav-active"); return label; }
    private void setBusy(Button button, boolean busy) {
        button.setDisable(busy);
        button.setGraphic(busy ? new ProgressIndicator() : (Node) button.getProperties().get("familyguard.icon"));
    }

    private <T> void complete(CompletableFuture<T> future, Consumer<T> success, Consumer<String> failure) {
        future.whenComplete((value, error) -> Platform.runLater(() -> {
            if (error == null) success.accept(value); else failure.accept(errorMessage(error));
        }));
    }

    private static String errorMessage(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? "No se pudo completar la operación." : current.getMessage();
    }

    private void setView(Node view) {
        view.setOpacity(0); view.setTranslateY(8); root.getChildren().setAll(view);
        FadeTransition fade = new FadeTransition(Duration.millis(230), view);
        fade.setFromValue(0); fade.setToValue(1);
        TranslateTransition move = new TranslateTransition(Duration.millis(230), view);
        move.setFromY(8); move.setToY(0); fade.play(); move.play();
    }
    private void info(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        alert.setTitle(title); alert.setHeaderText(title); styleDialog(alert.getDialogPane()); alert.showAndWait();
    }
    private boolean confirm(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle(title); alert.setHeaderText(title);
        styleDialog(alert.getDialogPane());
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private void styleDialog(javafx.scene.control.DialogPane pane) {
        ThemeManager.get().style(pane);
    }

    public static void main(String[] args) { launch(args); }
    private record PasswordFields(PasswordField field, HBox container) { String value() { return field.getText(); } }
    private record ChildInput(String name, LocalDate birthDate, String profileName) { }
}
