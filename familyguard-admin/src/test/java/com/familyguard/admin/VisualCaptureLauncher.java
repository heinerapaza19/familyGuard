package com.familyguard.admin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Genera evidencia visual reproducible contra el backend aislado del perfil test. */
public final class VisualCaptureLauncher extends Application {
    private static final String EMAIL = "captura.familyguard@example.test";
    private static final String PASSWORD = "ClaveVisual#2026";
    private static final Path OUTPUT = Path.of("docs", "captures");

    @Override
    public void start(Stage stage) throws Exception {
        ThemeManager.get().setTheme(ThemeManager.Theme.LIGHT);
        AdminApplication login = new AdminApplication();
        login.start(stage);
        captureAfter(stage, "login-light.png", Duration.seconds(1), () -> {
            ThemeManager.get().setTheme(ThemeManager.Theme.DARK);
            captureAfter(stage, "login-dark.png", Duration.seconds(1), () -> {
                ThemeManager.get().setTheme(ThemeManager.Theme.LIGHT);
                fire((Parent) stage.getScene().getRoot(), "Crear una cuenta familiar");
                captureAfter(stage, "registration-light.png", Duration.seconds(1), () -> {
                    fire((Parent) stage.getScene().getRoot(), "Volver al inicio de sesión");
                    showDashboard(stage);
                });
            });
        });
    }

    private void showDashboard(Stage stage) {
        AdminApiClient api = new AdminApiClient(URI.create("http://127.0.0.1:18080"));
        api.register("Revisión Visual", EMAIL, PASSWORD, PASSWORD, "Familia de revisión")
                .handle((ignored, registrationError) -> null)
                .thenCompose(ignored -> api.login(EMAIL, PASSWORD))
                .whenComplete((profile, error) -> Platform.runLater(() -> {
            if (error != null) {
                error.printStackTrace();
                Platform.exit();
                return;
            }
            DashboardWorkspace workspace = new DashboardWorkspace(api, profile, Platform::exit);
            Scene scene = new Scene(workspace, 1366, 768);
            ThemeManager.get().apply(scene);
            stage.setMinWidth(1020);
            stage.setMinHeight(680);
            stage.setScene(scene);
            stage.setWidth(1366);
            stage.setHeight(768);
            stage.centerOnScreen();
            stage.show();
            captureAfter(stage, "dashboard-light.png", Duration.seconds(3), () -> {
                stage.setWidth(1920); stage.setHeight(1080); stage.centerOnScreen();
                captureAfter(stage, "dashboard-light-1920x1080.png", Duration.seconds(1), () -> {
                    captureScreenSizes(stage,workspace,"Mis hijos","children",()->
                    captureScreenSizes(stage,workspace,"Dispositivos conectados","devices",()->
                    captureScreenSizes(stage,workspace,"Aplicaciones","applications",()->
                    captureScreenSizes(stage,workspace,"Control de sitios web","websites",()->
                    captureScreenSizes(stage,workspace,"Reportes","reports",()->
                    captureScreenSizes(stage,workspace,"Alertas","alerts",()->
                    captureScreenSizes(stage,workspace,"Mi perfil","profile",()->{
                        stage.setWidth(1366);stage.setHeight(768);stage.centerOnScreen();fire(workspace,"Inicio");
                        ThemeManager.get().setTheme(ThemeManager.Theme.DARK);
                        captureAfter(stage,"theme-switch-dark.png",Duration.seconds(1),()->{ThemeManager.get().setTheme(ThemeManager.Theme.LIGHT);Platform.exit();});
                    })))))));
                });
            });
                }));
    }

    private void captureScreenSizes(Stage stage,DashboardWorkspace workspace,String button,String prefix,Runnable next){
        fire(workspace,button);captureSize(stage,workspace,prefix,0,next);
    }
    private void captureSize(Stage stage,DashboardWorkspace workspace,String prefix,int index,Runnable next){
        int[][] sizes={{1366,768},{1600,900},{1920,1080}};
        if(index>=sizes.length){next.run();return;}
        stage.setWidth(sizes[index][0]);stage.setHeight(sizes[index][1]);stage.centerOnScreen();
        captureAfter(stage,prefix+"-"+sizes[index][0]+"x"+sizes[index][1]+".png",Duration.seconds(1),
                ()->captureSize(stage,workspace,prefix,index+1,next));
    }

    private void captureAfter(Stage stage, String fileName, Duration delay, Runnable next) {
        PauseTransition pause = new PauseTransition(delay);
        pause.setOnFinished(event -> {
            try {
                Files.createDirectories(OUTPUT);
                Scene scene = stage.getScene();
                WritableImage image = new WritableImage(
                        Math.max(1, (int) Math.ceil(scene.getWidth())),
                        Math.max(1, (int) Math.ceil(scene.getHeight())));
                scene.getRoot().snapshot(new SnapshotParameters(), image);
                String suffix = System.getProperty("familyguard.capture.suffix", "");
                String outputName = suffix.isBlank() ? fileName : fileName.replace(".png", "-" + suffix + ".png");
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", OUTPUT.resolve(outputName).toFile());
            } catch (IOException exception) {
                exception.printStackTrace();
                Platform.exit();
                return;
            }
            next.run();
        });
        pause.play();
    }

    private static void fire(Parent root, String text) {
        findButton(root, text).fire();
    }

    private static Button findButton(Parent parent, String text) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Button button && text.equals(button.getText())) return button;
            if (child instanceof Parent nested) {
                Button found = findButtonOrNull(nested, text);
                if (found != null) return found;
            }
        }
        throw new IllegalStateException("No se encontró el botón " + text);
    }

    private static Button findButtonOrNull(Parent parent, String text) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Button button && text.equals(button.getText())) return button;
            if (child instanceof Parent nested) {
                Button found = findButtonOrNull(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
