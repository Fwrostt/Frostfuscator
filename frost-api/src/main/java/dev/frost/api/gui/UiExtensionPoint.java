package dev.frost.api.gui;

/**
 * Interface allowing plugins to inject custom UI panels, tabs, menu items, or toolbar buttons into Frostfuscator's GUI.
 */
public interface UiExtensionPoint {

    enum ExtensionType {
        TOOLBAR_ACTION,
        MENU_ITEM,
        INSPECTOR_TAB,
        STRING_WORKBENCH_PANEL
    }

    /**
     * @return unique extension ID
     */
    String id();

    /**
     * @return display label
     */
    String label();

    /**
     * @return target GUI placement
     */
    ExtensionType type();

    /**
     * Action callback executed when user clicks the extension item.
     *
     * @param context GUI context payload containing selected class, archive path, and active editor
     */
    void onTrigger(Object context);
}
