package org.egomez.irpgeditor.swing;

import java.util.*;

import org.egomez.irpgeditor.env.*;

/**
 * @author not attributable
 */
public class ToolManager {
  HashMap mapPanels = new HashMap();
  HashMap mapPanelContainers = new HashMap();
  HashMap mapFactories = new HashMap();
  
  public void setPanelContainer(Class c, PanelToolContainer container) {
    mapPanelContainers.put(c, container);
  }
  
  public void setFactory(Class c, FactoryPanelTool factory) {
    mapFactories.put(c, factory);
  }
  
  protected PanelTool createPanelTool(Object object) {
    FactoryPanelTool factory;
    
    factory = (FactoryPanelTool)mapFactories.get(object.getClass());
    if ( factory == null ) {
      return null;
    }
    return factory.construct(object);
  }
  
  protected PanelToolContainer getPanelToolContainer(Class c) {
    return (PanelToolContainer)mapPanelContainers.get(c);
  }
  
  public Object getSelected(Class c) {
    PanelToolContainer panelContainer;
    PanelTool panelTool;
    Iterator iterator;
    Object key;
    
    panelContainer = getPanelToolContainer(c);
    panelTool = panelContainer.getSelectedPanel();
    iterator = mapPanels.keySet().iterator();
    key = iterator.next();
    while ( key != null ) {
      if ( mapPanels.get(key) == panelTool ) {
        return key;
      }
      key = iterator.next();
    }
    return null;
  }

  public boolean isCached(Object object) {
    return mapPanels.containsKey(object);
  }

  public PanelTool open(Object object) {
    PanelToolContainer panelContainer;
    PanelTool panelTool;
    
    panelContainer = getPanelToolContainer(object.getClass());
    panelTool = (PanelTool)mapPanels.get(object);
    if ( panelTool == null ) {
      panelTool = createPanelTool(object);
      if ( panelTool == null ) {
        return null;
      }
      mapPanels.put(object, panelTool);
      panelTool.setContainer(panelContainer);
    }
    else {
      // panel already exists in cache.
      // does it exist in the container?
      if ( panelContainer.indexOf(panelTool) == -1 ) {
        panelTool.setContainer(panelContainer);
        // must re add the actions from this panelTool to the environment
        // since they were removed when the panel was last closed before
        // it was cached.
        Environment.actions.addActions(panelTool.getActions());
      }
    }
    panelContainer.select(panelTool);
    return panelTool;
  }

  public void close(Object object, boolean cache) {
    PanelTool panelTool;

    panelTool = (PanelTool)mapPanels.get(object);
    if ( panelTool == null ) {
      return;
    }
    panelTool.setContainer(null);
    panelTool.close();
    if ( !cache ) {
      mapPanels.remove(object);
      panelTool.dispose();
    }
  }

  public void select(Object object) {
    PanelTool panelTool;

    panelTool = (PanelTool)mapPanels.get(object);
    if ( panelTool == null ) {
      return;
    }
    panelTool.requestFocus();
  }

  public void closeAll(Class c, boolean cache) {
    PanelToolContainer panelToolContainer;
    PanelTool[] panels;
    PanelTool panelTool;
    
    panelToolContainer = getPanelToolContainer(c);
    if ( panelToolContainer == null ) {
      return;
    }
    panels = panelToolContainer.getPanels();
    
    
//    panels = (PanelTool[])mapPanels.values().toArray(new PanelTool[mapPanels.values().size()]);
    for ( int x = 0; x < panels.length; x++ ) {
      panelTool = panels[x];
      panelTool.setContainer(null);
      panelTool.close();
      if ( !cache ) {
        panelTool.dispose();
        mapPanels.remove(getKey(panelTool));
      }
    }
  }
  
  protected Object getKey(Object value) {
    Iterator iterator;
    Object key;
    
    iterator = mapPanels.keySet().iterator();
    while ( iterator.hasNext() ) {
      key = iterator.next();
      if ( mapPanels.get(key) == value ) {
        return key;
      }
    }
    return null;
  }
}


