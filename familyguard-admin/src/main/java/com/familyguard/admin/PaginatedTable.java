package com.familyguard.admin;

import java.util.List;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Tabla con navegación real, total, límites y selector de tamaño. */
final class PaginatedTable<T> extends VBox {
    private final TableView<T> table;
    private final PaginationModel<T> model = new PaginationModel<>();
    private final Label summary = new Label();
    private final Label page = new Label();
    private final Button first = button("«"), previous = button("‹");
    private final Button next = button("›"), last = button("»");
    private final ComboBox<Integer> sizes = new ComboBox<>(FXCollections.observableArrayList(10,25,50,100));

    PaginatedTable(TableView<T> table) {
        this.table = table; getStyleClass().add("paginated-table"); setSpacing(8);
        sizes.setValue(10); sizes.setPrefWidth(84);
        sizes.setTooltip(new javafx.scene.control.Tooltip("Registros por página"));
        // Pie en una sola fila: «Mostrando X–Y de Z» y registros por página a la izquierda, navegación a la derecha.
        summary.getStyleClass().add("pagination-summary");page.getStyleClass().add("pagination-page");
        Label perPage=new Label("por página");perPage.getStyleClass().add("pagination-caption");
        Region navSpacer=new Region();HBox.setHgrow(navSpacer,Priority.ALWAYS);
        HBox pager=new HBox(4,first,previous,page,next,last);pager.setAlignment(Pos.CENTER_RIGHT);
        HBox footer=new HBox(10,summary,sizes,perPage,navSpacer,pager);footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("pagination-footer");
        first.setTooltip(new javafx.scene.control.Tooltip("Primera página"));previous.setTooltip(new javafx.scene.control.Tooltip("Página anterior"));
        next.setTooltip(new javafx.scene.control.Tooltip("Página siguiente"));last.setTooltip(new javafx.scene.control.Tooltip("Última página"));
        getChildren().addAll(table, footer); VBox.setVgrow(table,Priority.ALWAYS);
        first.setOnAction(e->{model.first();render();});previous.setOnAction(e->{model.previous();render();});
        next.setOnAction(e->{model.next();render();});last.setOnAction(e->{model.last();render();});
        sizes.setOnAction(e->{model.setPageSize(sizes.getValue());render();});
        table.setSortPolicy(ignored->{model.sort(table.getComparator());render();return true;});
    }
    TableView<T> table() { return table; }
    private int fitRows = -1;
    /**
     * La tabla toma el alto de las filas de la página (con un mínimo), en lugar de estirarse y dejar un hueco vacío
     * debajo de los registros. Usa el alto de fila que fija la hoja de estilos.
     */
    PaginatedTable<T> fitToRows(int minimumRows) {
        fitRows = Math.max(1, minimumRows);
        VBox.setVgrow(table, Priority.NEVER); getStyleClass().add("fit-rows");
        table.fixedCellSizeProperty().addListener(o -> fitHeight());
        fitHeight(); return this;
    }
    private void fitHeight() {
        if (fitRows < 0) return;
        double cell = table.getFixedCellSize() > 0 ? table.getFixedCellSize() : 46;
        int rows = Math.max(fitRows, table.getItems() == null ? 0 : table.getItems().size());
        double height = 46 + rows * cell + 4;   // cabecera + filas + borde
        table.setMinHeight(height); table.setPrefHeight(height); table.setMaxHeight(height);
    }
    void setItems(List<T> items) { model.setItems(items); render(); }
    void replaceItems(List<T> items) { model.replaceItems(items); render(); }
    List<T> allItems() { return model.total()==0?List.of():model.page(); }
    private void render() {
        table.setItems(FXCollections.observableArrayList(model.page()));
        fitHeight();
        summary.setText("Mostrando "+model.fromDisplay()+"–"+model.toDisplay()+" de "+model.total()+" registros");
        page.setText((model.pageIndex()+1)+" / "+model.pageCount());
        first.setDisable(!model.hasPrevious()); previous.setDisable(!model.hasPrevious());
        next.setDisable(!model.hasNext()); last.setDisable(!model.hasNext());
    }
    private static Button button(String text){Button value=new Button(text);value.getStyleClass().add("pagination-button");return value;}
}
