/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package org.komodo.web.client.panels.vdb.editor.diag.tree;

import java.util.logging.Logger;
import org.komodo.spi.repository.KomodoType;
import org.komodo.web.client.panels.vdb.editor.diag.DiagramCss;
import org.komodo.web.client.resources.AppResource;
import org.komodo.web.share.Constants;
import com.github.gwtd3.api.Coords;
import com.github.gwtd3.api.D3;
import com.github.gwtd3.api.arrays.Array;
import com.github.gwtd3.api.arrays.ForEachCallback;
import com.github.gwtd3.api.core.Selection;
import com.github.gwtd3.api.core.Value;
import com.github.gwtd3.api.functions.DatumFunction;
import com.github.gwtd3.api.functions.KeyFunction;
import com.github.gwtd3.api.layout.HierarchicalLayout.Node;
import com.github.gwtd3.api.layout.Link;
import com.github.gwtd3.api.svg.Diagonal;
import com.github.gwtd3.api.svg.PathDataGenerator;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.dom.client.Element;
import com.google.gwt.resources.client.ImageResource;

/**
 *
 */
public abstract class TreeCanvasUtilities implements Constants {

    protected static final Logger LOGGER = Logger.getLogger(TreeCanvasUtilities.class.getName());

    /**
     * Updates all the new, updated and removed nodes
     *
     * The source is the node at the "top" of the section
     * being explanded/collapsed. Normally, this would be root
     * but when a node is clicked on to be expanded then it
     * would be the clicked node.
     */
    protected abstract void update(final TreeNode source);

    /**
     * @return the css stylesheet for this canvas
     */
    protected abstract DiagramCss css();

    /**
     * Deduce the selection and fire an event
     */
    protected abstract void fireSelectionEvent();

    /**
     * @param e element
     * @return the bounding width of the given element
     */
    public static final native int getBoundingWidth(Element e) /*-{
		return e.getBBox().width;
    }-*/;

    /**
     * @param e element
     * @return the bounding height of the given element
     */
    public static final native int getBoundingHeight(Element e) /*-{
		return e.getBBox().height;
    }-*/;

    /**
     * @param e element
     * @param namespace namespace of the attribute
     * @param attribute the attribute
     * @param value the value of the attribute
     */
    public static final native void setElementAttributeNS(Element e,
                                                                                              String namespace,
                                                                                              String attribute,
                                                                                              String value) /*-{
        return e.setAttributeNS(namespace, attribute, value);
    }-*/;

    /**
     * The projection callback for the link generator.
     * See {@link Diagonal#projection()} for details.
     */
    protected class PathProjectionCallback implements DatumFunction<Array<Double>> {
        @Override
        public Array<Double> apply(Element element, Value jsNode, int elementIndex) {
            TreeNode treeNode = jsNode.<TreeNode> as();
            return Array.fromDoubles(treeNode.x(), treeNode.y());
        }
    }

    /**
     * The callback supplied to the zoom listener
     * that controls zooming and panning.
     */
    protected class ZoomListenerCallback implements DatumFunction<Void> {

        private Selection parentSelection;

        /**
         * @param parentSelection the selection spanning the whole diagram
         */
        public ZoomListenerCallback(Selection parentSelection) {
            this.parentSelection = parentSelection;
        }

        @Override
        public Void apply(Element context, Value jsNode, int index) {
            if (parentSelection == null)
                return null;

            Array<Double> eventTranslate = D3.zoomEvent().translate();
            double eventScale = D3.zoomEvent().scale();

            StringBuffer transform = new StringBuffer();
            transform.append(SVG_TRANSLATE).append(OPEN_BRACKET)
                           .append(eventTranslate).append(CLOSE_BRACKET)
                           .append(SVG_SCALE).append(OPEN_BRACKET)
                           .append(eventScale).append(CLOSE_BRACKET);

            parentSelection.attr(SVG_TRANSFORM, transform.toString());
            return null;
        }
    }

    /**
     * Callback to collapse a node's children into itself.
     */
    protected class CollapseCallback implements ForEachCallback<Void> {
        @Override
        public Void forEach(Object thisArg, Value element, int index, Array<?> array) {

            TreeNode treeNode = element.<TreeNode>as();
            Array<Node> children = treeNode.children();
            if (children != null) {
                treeNode.setAttr(JS_NO_CHILDREN, children);
                treeNode.getObjAttr(JS_NO_CHILDREN).<Array<Node>>cast().forEach(this);
                treeNode.setAttr(JS_CHILDREN, null);
            }
            return null;
        }
    }

    /**
     * The listener callback that controls
     * the expanding/collapsing of the nodes in the tree
     */
    protected class ExpandCollapseListener implements DatumFunction<Void> {
        @Override
        public Void apply(Element context, Value jsNode, int index) {
            TreeNode treeNode = jsNode.<TreeNode> as();
            if (treeNode.children() != null) {
                treeNode.setAttr(JS_NO_CHILDREN, treeNode.children());
                treeNode.setAttr(JS_CHILDREN, null);
            } else {
                treeNode.setAttr(JS_CHILDREN, treeNode.getObjAttr(JS_NO_CHILDREN));
                treeNode.setAttr(JS_NO_CHILDREN, null);
            }
            update(treeNode);

            D3.event().stopPropagation();

            return null;
        }
    }

    /**
     * The selection listener callback that controls
     * the display of the selection rectangle and the
     * collection of selection nodes.
     */
    protected class SelectionListener implements DatumFunction<Void> {

        private Selection parentSelection;

        /**
         * @param parentSelection the selection spanning the whole diagram
         */
        public SelectionListener(Selection parentSelection) {
            this.parentSelection = parentSelection;
        }

        @Override
        public Void apply(Element element, Value jsNode, int index) {
            if (! D3.event().getShiftKey()) {
                /*
                 * As long as the shift key is not pressed, remove all selected
                 * elements by finding all elements with the css selected class
                 */
                String selectedClass = DOT + css().selected();
                parentSelection.selectAll(selectedClass).remove();
            }

            String id = element.getId();
            try {
                if (id == null)
                    return null;

                if (! id.startsWith(NODE_ID_PREFIX))
                    return null;

                int boundingWidth = getBoundingWidth(element);
                int boundingHeight = getBoundingHeight(element);

                D3.select(element).insert(SVG_RECTANGLE, HTML_TEXT)
                                             .attr(HTML_X, -(boundingWidth / 2) - 5)
                                             .attr(HTML_Y, -boundingHeight)
                                             .attr(HTML_WIDTH, boundingWidth + 10)
                                             .attr(HTML_HEIGHT, boundingHeight)
                                             .classed(css().selected(), true);
            } finally {
                // Stop propagration of click event to parent svg
                D3.event().stopPropagation();

                // Broadcast the latest selection
                fireSelectionEvent();
            }

            return null;
        }
    }

    /**
     * Callback that determines whether a
     * the style fill colour whether a node has
     * children or not.
     */
    protected class ChildStatusCallback implements DatumFunction<String> {
        @Override
        public String apply(Element context, Value jsNode, int index) {
            JavaScriptObject object = jsNode.<TreeNode> as().getObjAttr(JS_NO_CHILDREN);
            Value hasChildrenValue = jsNode.getProperty(HAS_CHILDREN);
            String hasChildrenString = hasChildrenValue.asString();
            boolean hasChildren = Boolean.parseBoolean(hasChildrenString);

            /*
             * If we have children then return the colour black else return white
             * Used for the fill style in the circles beneath the images
             */
            return (object != null || hasChildren) ? "#000" : "#fff"; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The callback used to map the identifiers of
     * the nodes displayed.
     */
    protected class NodeIdMappingCallback implements KeyFunction<String> {
        @Override
        public String map(Element context, Array<?> newDataArray, Value jsNode, int index) {
            /*
             * jsNode has the properties gathered from the key/values
             * in the json data presented to the layout initially.
             */
            TreeNode treeNode = jsNode.<TreeNode> as();
            Value idValue = jsNode.getProperty(ID);
            String id = idValue.asString();
            return ((treeNode.id() == EMPTY_STRING) ? treeNode.id(id) : treeNode.id());
        }
    }

    /**
     * The callback used to map the identifies of
     * the links displayed.
     */
    protected class LinkIdMappingCallback implements KeyFunction<String> {
        @Override
        public String map(Element context, Array<?> newDataArray, Value jsNode, int index) {
            return jsNode.<Link> as().target().<TreeNode> cast().id();
        }
    }

    /**
     * The callback used to generate the links
     */
    protected class LinkGeneratorCallback implements DatumFunction<String> {

        private final PathDataGenerator generator;
        private final double x;
        private final double y;

        /**
         * @param generator the link generator implementation
         * @param x x location
         * @param y y location
         */
        public LinkGeneratorCallback(PathDataGenerator generator, double x, double y) {
            this.generator = generator;
            this.x = x;
            this.y = y;
        }

        @Override
        public String apply(Element context, Value jsNode, int index) {
            Coords o = Coords.create(x, y);
            /*
             * Use the path data generator to create a link that at
             * this point goes from source to source
             */
            return generator.generate(Link.create(o, o));
        }
      }

    /**
     * Generic property callback used to find and return
     * the property value of the property with the given
     * name.
     */
    protected class StringPropertyCallback implements DatumFunction<String> {

        private final String propertyName;

        /**
         * @param propertyName the name of the property
         */
        public StringPropertyCallback(String propertyName) {
            this.propertyName = propertyName;
        }
        
        @Override
        public String apply(Element context, Value jsNode, int index) {
            Value propertyValue = jsNode.getProperty(propertyName);
            String property = propertyValue.asString();
            return property;
        }
    }

    /**
     * Generic property callback used to find and return
     * the property value of the property with the given
     * name.
     */
    protected class ImageResourceCallback implements DatumFunction<Void> {

        @Override
        public Void apply(Element context, Value jsNode, int index) {
            Value propertyValue = jsNode.getProperty(TYPE);
            String kType = propertyValue.asString();
            ImageResource imageResource = getImage(KomodoType.getKomodoType(kType));

            /*
             * setAttribute will just set the attribute name to xlink:href and not
             * treat it as a ns prefix.
             */
            setElementAttributeNS(context, XLINK_NAMESPACE, HTML_HREF, imageResource.getSafeUri().asString());
            context.setAttribute(HTML_WIDTH, Integer.toString(imageResource.getWidth()));
            context.setAttribute(HTML_HEIGHT, Integer.toString(imageResource.getHeight()));
            return null;
        }
    }

    /**
     * The callback used to calculate the Y co-ordinate
     * of a node given the constants of height between
     * parent/child and the extra top margin.
     */
    protected class YNodeLocationCallback implements ForEachCallback<Void> {

        private final int depthHeight;
        private final int topMargin;

        /**
         * @param depthHeight distance between parent/children
         * @param topMargin margin above node
         */
        public YNodeLocationCallback(int depthHeight, int topMargin) {
            this.depthHeight = depthHeight;
            this.topMargin = topMargin;
        }

        @Override
        public Void forEach(Object thisArg, Value jsNode, int index, Array<?> array) {
            TreeNode treeNode = jsNode.<TreeNode> as();
            double value = (treeNode.depth() * depthHeight) + topMargin;
            treeNode.setAttr(HTML_Y, value);
            return null;
        }
    }

    /**
     * Callback used to calculate the final location
     * of a node based on its data location
     */
    protected class NodeUpdateLocationCallback implements ForEachCallback<Void> {
        @Override
        public Void forEach(Object thisArg, Value jsNode, int index, Array<?> array) {
            TreeNode treeNode = jsNode.<TreeNode> as();
            treeNode.setAttr(HTML_X0, treeNode.x());
            treeNode.setAttr(HTML_Y0, treeNode.y());
            return null;
        }
    }

    /**
     * Callback used to determine the id attribute
     * of a node's group element.
     */
    protected class GroupIdCallback implements DatumFunction<String> {
        @Override
        public String apply(Element context, Value jsNode, int index) {
            TreeNode treeNode = jsNode.<TreeNode> as();
            return NODE_ID_PREFIX + treeNode.id();
        }
    }

    /**
     * Callback used to determine the translate attribute
     * of new nodes being added.
     */
    protected class NodeEnterTransformCallback implements DatumFunction<String> {
        @Override
        public String apply(Element context, Value jsNode, int index) {
            TreeNode treeNode = jsNode.<TreeNode> as();
            return SVG_TRANSLATE + OPEN_BRACKET +
                        treeNode.x() + COMMA +
                        treeNode.y() + CLOSE_BRACKET;
        }
    }

    /**
     * Callback used to determine the translate attribute
     * of nodes being removed.
     */
    protected class NodeExitTransformCallback implements DatumFunction<String> {

        private final TreeNode source;

        /**
         * @param source the parent node
         */
        public NodeExitTransformCallback(TreeNode source) {
            this.source = source;
        }

        @Override
        public String apply(Element context, Value jsNode, int index) {
            return SVG_TRANSLATE + OPEN_BRACKET +
                        source.x() + COMMA +
                        source.y() + CLOSE_BRACKET;
        }
    }

    /**
     * @param kType the komodo type
     * @return the associated image for the given komodo type
     */
    public static ImageResource getImage(KomodoType kType) {
        if (kType == null)
            kType = KomodoType.UNKNOWN;

        switch (kType) {
            case VDB:
                return AppResource.INSTANCE.images().diagVdb_Image();
            case MODEL:
                return AppResource.INSTANCE.images().diagModel_Image();
            case VDB_MODEL_SOURCE:
                return AppResource.INSTANCE.images().diagSource_Image();
            case VDB_TRANSLATOR:
                return AppResource.INSTANCE.images().diagTranslator_Image();
            case VDB_IMPORT:
                return AppResource.INSTANCE.images().diagVdbImport_Image();
            case VDB_DATA_ROLE:
                return AppResource.INSTANCE.images().diagDataRole_Image();
            case VDB_PERMISSION:
                return AppResource.INSTANCE.images().diagPermission_Image();
            case VDB_CONDITION:
                return AppResource.INSTANCE.images().diagCondition_Image();
            case VIEW:
                return AppResource.INSTANCE.images().diagView_Image();
            case PARAMETER:
                return AppResource.INSTANCE.images().diagParameter_Image();
            case TABULAR_RESULT_SET:
                return AppResource.INSTANCE.images().diagResultSet_Image();
            case DATA_TYPE_RESULT_SET:
                return AppResource.INSTANCE.images().diagDataTypeResultSet_Image();
            case VDB_MASK:
                return AppResource.INSTANCE.images().diagMask_Image();
            case ACCESS_PATTERN:
                return AppResource.INSTANCE.images().diagAccessPattern_Image();
            case COLUMN:
                return AppResource.INSTANCE.images().diagColumn_Image();
            case RESULT_SET_COLUMN:
                return AppResource.INSTANCE.images().diagResultSetColumn_Image();
            case USER_DEFINED_FUNCTION:
                return AppResource.INSTANCE.images().diagUsedDefinedFunction_Image();
            case UNIQUE_CONSTRAINT:
                return AppResource.INSTANCE.images().diagUniqueConstraint_Image();
            case TABLE:
                return AppResource.INSTANCE.images().diagTable_Image();
            case STORED_PROCEDURE:
                return AppResource.INSTANCE.images().diagStoredProcedure_Image();
            case VIRTUAL_PROCEDURE:
                return AppResource.INSTANCE.images().diagVirtualProcedure_Image();
            case STATEMENT_OPTION:
                return AppResource.INSTANCE.images().diagStatementOption_Image();
            case SCHEMA:
                return AppResource.INSTANCE.images().diagSchema_Image();
            case PUSHDOWN_FUNCTION:
                return AppResource.INSTANCE.images().diagPushdownFunction_Image();
            case INDEX:
                return AppResource.INSTANCE.images().diagIndex_Image();
            case FOREIGN_KEY:
                return AppResource.INSTANCE.images().diagForeignKey_Image();
            case PRIMARY_KEY:
                return AppResource.INSTANCE.images().diagPrimaryKey_Image();
            case VDB_ENTRY:
                return AppResource.INSTANCE.images().diagVdbEntry_Image();
            case DDL_SCHEMA:
                return AppResource.INSTANCE.images().diagDdl_Image();
            case TSQL_SCHEMA:
                return AppResource.INSTANCE.images().diagTeiidSql_Image();
            case VDB_SCHEMA:
                return AppResource.INSTANCE.images().diagVdbSchema_Image();
            default:
                return AppResource.INSTANCE.images().diagDefault_Image();
        }
    }
}
