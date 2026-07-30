package dev.frost.obfuscator.gui.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphViewerDirectionTest {
    @Test
    void breadthFirstDirectionsUseCytoscapeValues() {
        assertEquals("rightward", GraphViewer.FlowDirection.LEFT_TO_RIGHT.rendererValue());
        assertEquals("downward", GraphViewer.FlowDirection.TOP_TO_BOTTOM.rendererValue());
    }
}
