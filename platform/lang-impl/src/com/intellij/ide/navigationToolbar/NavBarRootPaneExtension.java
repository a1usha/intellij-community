// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.navigationToolbar;

import com.intellij.ide.navigationToolbar.ui.NavBarUIManager;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.ui.UISettingsListener;
import com.intellij.ide.ui.customization.CustomActionsSchema;
import com.intellij.ide.ui.customization.CustomisedActionGroup;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.IdeRootPaneNorthExtension;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.util.ui.JBSwingUtilities;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * @author Konstantin Bulenkov
 */
public final class NavBarRootPaneExtension extends IdeRootPaneNorthExtension {
  private static final Logger LOG = Logger.getInstance(NavBarRootPaneExtension.class);

  private JComponent myWrapperPanel;
  @NonNls public static final String NAV_BAR = "NavBar";
  @SuppressWarnings("StatefulEp")
  private final Project myProject;
  private NavBarPanel myNavigationBar;
  private JPanel myRunPanel;
  private Boolean myNavToolbarGroupExist;
  private JScrollPane myScrollPane;

  public NavBarRootPaneExtension(@NotNull Project project) {
    myProject = project;

    myProject.getMessageBus().connect().subscribe(UISettingsListener.TOPIC, uiSettings -> {
      toggleRunPanel(isShowToolPanel(uiSettings));
    });
  }

  private static boolean isShowToolPanel(@NotNull UISettings uiSettings) {
    return uiSettings.getShowToolbarInNavigationBar() && !uiSettings.getPresentationMode();
  }

  @Override
  public void revalidate() {
    final UISettings settings = UISettings.getInstance();
    LOG.debug("Revalidate in the navbarRootPane, toolbar visible: " + isShowToolPanel(settings));
    if (isShowToolPanel(settings)) {
      toggleRunPanel(false);
      toggleRunPanel(true);
    }
  }

  @Override
  public IdeRootPaneNorthExtension copy() {
    return new NavBarRootPaneExtension(myProject);
  }

  public boolean isMainToolbarVisible() {
    var b = !UISettings.getInstance().getPresentationMode() &&
           (UISettings.getInstance().getShowMainToolbar() || !runToolbarExists());
    LOG.debug("Toolbar visibility: " + b);
    return  b;
  }

  private boolean runToolbarExists() {
    if (myNavToolbarGroupExist == null) {
      final AnAction correctedAction = CustomActionsSchema.getInstance().getCorrectedAction(IdeActions.GROUP_NAVBAR_TOOLBAR);
      myNavToolbarGroupExist = correctedAction instanceof DefaultActionGroup && ((DefaultActionGroup)correctedAction).getChildrenCount() > 0 ||
             correctedAction instanceof CustomisedActionGroup && ((CustomisedActionGroup)correctedAction).getFirstAction() != null;
    }
    return myNavToolbarGroupExist;
  }

  @NotNull
  @Override
  public JComponent getComponent() {
    if (myWrapperPanel == null) {
      myWrapperPanel = new NavBarWrapperPanel(new BorderLayout()) {
        @Override
        protected void paintComponent(Graphics g) {
          super.paintComponent(g);
          NavBarUIManager.getUI().doPaintWrapperPanel((Graphics2D)g, getBounds(), isMainToolbarVisible());
        }

        @Override
        public Insets getInsets() {
          return NavBarUIManager.getUI().getWrapperPanelInsets(super.getInsets());
        }
      };

      addNavigationBarPanel(myWrapperPanel);
      toggleRunPanel(isShowToolPanel(UISettings.getInstance()));
    }
    return myWrapperPanel;
  }

  private void addNavigationBarPanel(JComponent wrapperPanel) {
    wrapperPanel.add(buildNavBarPanel(), BorderLayout.CENTER);
  }

  public static class NavBarWrapperPanel extends JPanel {
    public NavBarWrapperPanel(LayoutManager layout) {
      super(layout);
      setName("navbar");
    }

    @Override
    protected Graphics getComponentGraphics(Graphics graphics) {
      return JBSwingUtilities.runGlobalCGTransform(this, super.getComponentGraphics(graphics));
    }
  }

  private static void alignVertically(Container container) {
    if (container.getComponentCount() == 1) {
      Component c = container.getComponent(0);
      Insets insets = container.getInsets();
      Dimension d = c.getPreferredSize();
      Rectangle r = container.getBounds();
      c.setBounds(insets.left, (r.height - d.height - insets.top - insets.bottom) / 2 + insets.top, r.width - insets.left - insets.right,
                  d.height);
    }
  }

  private void toggleRunPanel(final boolean show) {
    if (show && myRunPanel == null && runToolbarExists()) {
      final ActionManager manager = ActionManager.getInstance();
      AnAction toolbarRunGroup = CustomActionsSchema.getInstance().getCorrectedAction(IdeActions.GROUP_NAVBAR_TOOLBAR);

      if (toolbarRunGroup instanceof ActionGroup && myWrapperPanel != null) {
        ActionToolbar actionToolbar = manager.createActionToolbar(ActionPlaces.NAVIGATION_BAR_TOOLBAR, (ActionGroup)toolbarRunGroup, true);
        actionToolbar.setTargetComponent(null);
        myRunPanel = new JPanel(new BorderLayout()) {
          @Override
          public void doLayout() {
            alignVertically(this);
          }
        };
        myRunPanel.setOpaque(false);
        myRunPanel.add(actionToolbar.getComponent(), BorderLayout.CENTER);
        final boolean needGap = isNeedGap(toolbarRunGroup);
        myRunPanel.setBorder(JBUI.Borders.emptyLeft(needGap ? 5 : 1));
        myWrapperPanel.add(myRunPanel, BorderLayout.EAST);
      }
    }
    else if (!show && myRunPanel != null) {
      myWrapperPanel.remove(myRunPanel);
      myRunPanel = null;
    }
  }

  private static boolean isNeedGap(final AnAction group) {
    final AnAction firstAction = getFirstAction(group);
    return firstAction instanceof ComboBoxAction;
  }

  @Nullable
  private static AnAction getFirstAction(final AnAction group) {
    if (group instanceof DefaultActionGroup) {
      AnAction firstAction = null;
      for (final AnAction action : ((DefaultActionGroup)group).getChildActionsOrStubs()) {
        if (action instanceof DefaultActionGroup) {
          firstAction = getFirstAction(action);
        }
        else if (action instanceof Separator || action instanceof ActionGroup) {
          continue;
        }
        else {
          firstAction = action;
          break;
        }

        if (firstAction != null) break;
      }

      return firstAction;
    }
    if (group instanceof CustomisedActionGroup) {
      return ((CustomisedActionGroup)group).getFirstAction();
    }
    return null;
  }

  private JComponent buildNavBarPanel() {
    myNavigationBar = new NavBarPanel(myProject, true);
    myWrapperPanel.putClientProperty("NavBarPanel", myNavigationBar);
    myNavigationBar.getModel().setFixedComponent(true);
    myScrollPane = ScrollPaneFactory.createScrollPane(myNavigationBar);

    JPanel panel = new JPanel(new BorderLayout()) {

      @Override
      protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        final Component navBar = myScrollPane;
        Insets insets = getInsets();
        Rectangle r = navBar.getBounds();

        Graphics2D g2d = (Graphics2D)g.create();
        g2d.translate(r.x, r.y);
        g2d.dispose();
      }

      @Override
      public void doLayout() {
        // align vertically
        final Rectangle r = getBounds();
        final Insets insets = getInsets();
        int x = insets.left;
        if (myScrollPane == null) return;
        final Component navBar = myScrollPane;

        final Dimension preferredSize = navBar.getPreferredSize();

        navBar.setBounds(x, (r.height - preferredSize.height) / 2,
                         r.width - insets.left - insets.right, preferredSize.height);
      }

      @Override
      public void updateUI() {
        super.updateUI();
        setOpaque(true);
        if (myScrollPane == null || myNavigationBar == null) return;

        myScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        myScrollPane.setHorizontalScrollBar(null);
        myScrollPane.setBorder(new NavBarBorder());
        myScrollPane.setOpaque(false);
        myScrollPane.getViewport().setOpaque(false);
        myScrollPane.setViewportBorder(null);
        myNavigationBar.setBorder(null);
      }
    };

    panel.add(myScrollPane, BorderLayout.CENTER);
    panel.updateUI();
    return panel;
  }

  @Override
  public void uiSettingsChanged(@NotNull UISettings settings) {
    if (myNavigationBar == null) {
      return;
    }

    myNavigationBar.updateState(settings.getShowNavigationBar());
    myWrapperPanel.setVisible(settings.getShowNavigationBar() && !UISettings.getInstance().getPresentationMode());

    myWrapperPanel.revalidate();
    myNavigationBar.revalidate();
    myWrapperPanel.repaint();

    if (myWrapperPanel.getComponentCount() > 0) {
      Component c = myWrapperPanel.getComponent(0);
      if (c instanceof JComponent) {
        ((JComponent)c).setOpaque(false);
      }
    }
  }

  @Override
  @NotNull
  public String getKey() {
    return NAV_BAR;
  }
}
