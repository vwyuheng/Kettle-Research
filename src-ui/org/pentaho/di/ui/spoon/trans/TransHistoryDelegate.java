/*
 * Copyright (c) 2010 Pentaho Corporation.  All rights reserved. 
 * This software was developed by Pentaho Corporation and is provided under the terms 
 * of the GNU Lesser General Public License, Version 2.1. You may not use 
 * this file except in compliance with the license. If you need a copy of the license, 
 * please go to http://www.gnu.org/licenses/lgpl-2.1.txt. The Original Code is Pentaho 
 * Data Integration.  The Initial Developer is Pentaho Corporation.
 *
 * Software distributed under the GNU Lesser Public License is distributed on an "AS IS" 
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or  implied. Please refer to 
 * the license for the specific language governing your rights and limitations.
 */
package org.pentaho.di.ui.spoon.trans;

import java.sql.ResultSet;
import java.util.*;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.Props;
import org.pentaho.di.core.RowMetaAndData;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleValueException;
import org.pentaho.di.core.logging.ChannelLogTable;
import org.pentaho.di.core.logging.LogChannel;
import org.pentaho.di.core.logging.LogStatus;
import org.pentaho.di.core.logging.LogTableField;
import org.pentaho.di.core.logging.LogTableInterface;
import org.pentaho.di.core.row.ValueMeta;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.core.dialog.ErrorDialog;
import org.pentaho.di.ui.core.gui.GUIResource;
import org.pentaho.di.ui.core.widget.ColumnInfo;
import org.pentaho.di.ui.core.widget.TableView;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.XulSpoonResourceBundle;
import org.pentaho.di.ui.spoon.XulSpoonSettingsManager;
import org.pentaho.di.ui.spoon.delegates.SpoonDelegate;
import org.pentaho.ui.xul.XulDomContainer;
import org.pentaho.ui.xul.XulLoader;
import org.pentaho.ui.xul.components.XulToolbarbutton;
import org.pentaho.ui.xul.containers.XulToolbar;
import org.pentaho.ui.xul.impl.XulEventHandler;
import org.pentaho.ui.xul.swt.SwtXulLoader;

public class TransHistoryDelegate extends SpoonDelegate implements XulEventHandler {
  private static Class<?> PKG = Spoon.class; // for i18n purposes, needed by Translator2!!   $NON-NLS-1$

  private static final String XUL_FILE_TRANS_GRID_TOOLBAR = "ui/trans-history-toolbar.xul"; //$NON-NLS-1$

  private TransGraph transGraph;

  private CTabItem transHistoryTab;

  private List<ColumnInfo[]> columns;

  private List<List<LogTableField>> logTableFields;

  private List<Text> wText;

  private List<TableView> wFields;

  private List<List<Object[]>> rowList;

  private boolean displayRefreshNeeded = true;

  private boolean refreshNeeded = true;

  private Object refreshNeededLock = new Object();

  private XulToolbar toolbar;

  private Composite transHistoryComposite;

  private List<LogTableInterface> logTables;

  private TransMeta transMeta;

  private CTabFolder tabFolder;

  protected boolean gettingHistoryData;

  /**
   * @param spoon
   * @param transGraph
   */
  public TransHistoryDelegate(Spoon spoon, TransGraph transGraph) {
    super(spoon);
    this.transGraph = transGraph;
  }

  public void addTransHistory() {
    // First, see if we need to add the extra view...
    //
    if (transGraph.extraViewComposite == null || transGraph.extraViewComposite.isDisposed()) {
      transGraph.addExtraView();
    } else {
      if (transHistoryTab != null && !transHistoryTab.isDisposed()) {
        // just set this one active and get out...
        //
        transGraph.extraViewTabFolder.setSelection(transHistoryTab);
        return;
      }
    }

    transMeta = transGraph.getManagedObject();

    // Add a transLogTab : display the logging...
    //
    transHistoryTab = new CTabItem(transGraph.extraViewTabFolder, SWT.NONE);
    transHistoryTab.setImage(GUIResource.getInstance().getImageShowHistory());
    transHistoryTab.setText(BaseMessages.getString(PKG, "Spoon.TransGraph.HistoryTab.Name")); //$NON-NLS-1$

    // Create a composite, slam everything on there like it was in the history tab.
    //
    transHistoryComposite = new Composite(transGraph.extraViewTabFolder, SWT.NONE);
    transHistoryComposite.setLayout(new FormLayout());
    spoon.props.setLook(transHistoryComposite);

    addToolBar();

    Control toolbarControl = (Control) toolbar.getManagedObject();
    
    toolbarControl.setLayoutData(new FormData());
    FormData fd = new FormData();
    fd.left = new FormAttachment(0, 0); // First one in the left top corner
    fd.top = new FormAttachment(0, 0);
    fd.right = new FormAttachment(100, 0);
    toolbarControl.setLayoutData(fd);
    
    toolbarControl.setParent(transHistoryComposite);
    
    addLogTableTabs();
    tabFolder.setSelection(0);

    transHistoryComposite.pack();
    transHistoryTab.setControl(transHistoryComposite);
    transGraph.extraViewTabFolder.setSelection(transHistoryTab);

    // Launch a refresh in the background...
    //
    refreshHistory();

    Timer timer = new Timer();
    TimerTask timerTask = new TimerTask() {
      public void run() {
        if (displayRefreshNeeded) {
          displayRefreshNeeded = false;
          synchronized(rowList){
            for (int i = 0; i < logTables.size(); i++) {
              final int index = i;
              spoon.getDisplay().syncExec(new Runnable() {

                public void run() {
                  if (!Const.isEmpty(rowList) && index < rowList.size()) {
                   displayHistoryData(logTables.get(index), index, rowList.get(index));
                  }
                }
              });
            }
          }
        }
      };
    };

    // Try to refresh every second or so...
    //
    timer.schedule(timerTask, 1000, 1000);

  }

  private void addLogTableTabs() {

    logTables = transMeta.getLogTables();

    columns = new ArrayList<ColumnInfo[]>();
    rowList = new ArrayList<List<Object[]>>();
    logTableFields = new ArrayList<List<LogTableField>>();
    wFields = new ArrayList<TableView>();
    wText = new ArrayList<Text>();

    // Create a nested tab folder in the tab item, on the history composite...
    //
    tabFolder = new CTabFolder(transHistoryComposite, SWT.MULTI);
    spoon.props.setLook(tabFolder, Props.WIDGET_STYLE_TAB);

    for (LogTableInterface logTable : logTables) {
      CTabItem tabItem = new CTabItem(tabFolder, SWT.NONE);
      // tabItem.setImage(GUIResource.getInstance().getImageShowHistory());
      tabItem.setText(logTable.getLogTableType());

      Composite logTableComposite = new Composite(tabFolder, SWT.NONE);
      logTableComposite.setLayout(new FormLayout());
      spoon.props.setLook(logTableComposite);

      tabItem.setControl(logTableComposite);

      SashForm sash = new SashForm(logTableComposite, SWT.VERTICAL);
      sash.setLayout(new FillLayout());
      FormData fdSash = new FormData();
      fdSash.left = new FormAttachment(0, 0); // First one in the left top corner
      fdSash.top = new FormAttachment(0, 0);
      fdSash.right = new FormAttachment(100, 0);
      fdSash.bottom = new FormAttachment(100, 0);
      sash.setLayoutData(fdSash);

      List<ColumnInfo> columnList = new ArrayList<ColumnInfo>();
      List<LogTableField> fields = new ArrayList<LogTableField>();

      for (LogTableField field : logTable.getFields()) {
        if (field.isEnabled() && field.isVisible()) {
          fields.add(field);
          if (!field.isLogField()) {
            ColumnInfo column = new ColumnInfo(field.getName(), ColumnInfo.COLUMN_TYPE_TEXT, false, true);
            ValueMetaInterface valueMeta = new ValueMeta(field.getFieldName(), field.getDataType(), field.getLength(), -1);

            switch (field.getDataType()) {
              case ValueMetaInterface.TYPE_INTEGER:
                valueMeta.setConversionMask("###,###,##0"); //$NON-NLS-1$
                column.setAllignement(SWT.RIGHT);
                break;
              case ValueMetaInterface.TYPE_DATE:
                valueMeta.setConversionMask("yyyy/MM/dd HH:mm:ss"); //$NON-NLS-1$
                column.setAllignement(SWT.CENTER);
                break;
              case ValueMetaInterface.TYPE_NUMBER:
                valueMeta.setConversionMask(" ###,###,##0.00;-###,###,##0.00"); //$NON-NLS-1$
                column.setAllignement(SWT.RIGHT);
                break;
              case ValueMetaInterface.TYPE_STRING:
                column.setAllignement(SWT.LEFT);
                break;
              case ValueMetaInterface.TYPE_BOOLEAN:
                DatabaseMeta databaseMeta = logTable.getDatabaseMeta(); 
                if (databaseMeta!=null) {
                  if (!databaseMeta.supportsBooleanDataType()) {
                    // Boolean gets converted to String!
                    //
                    valueMeta.setType(ValueMetaInterface.TYPE_STRING);
                  }
                }
                break;
            }
            column.setValueMeta(valueMeta);
            columnList.add(column);
          }
        }
      }
      logTableFields.add(fields);

      final int FieldsRows = 1;
      ColumnInfo[] colinf = columnList.toArray(new ColumnInfo[columnList.size()]);
      columns.add(colinf); // keep for later

      TableView tableView = new TableView(transGraph.getManagedObject(), sash, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE, colinf, FieldsRows, true, // readonly!
          null, spoon.props);
      wFields.add(tableView);

      tableView.table.addSelectionListener(new SelectionAdapter() {
        public void widgetSelected(SelectionEvent arg0) {
          showLogEntry();
        }
      });

      if (logTable.getLogField() != null) {

        Text text = new Text(sash, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        spoon.props.setLook(text);
        text.setVisible(true);
        wText.add(text);

        FormData fdText = new FormData();
        fdText.left = new FormAttachment(0, 0);
        fdText.top = new FormAttachment(0, 0);
        fdText.right = new FormAttachment(100, 0);
        fdText.bottom = new FormAttachment(100, 0);
        text.setLayoutData(fdText);

        sash.setWeights(new int[] { 70, 30, });
      } else {
        wText.add(null);
        sash.setWeights(new int[] { 100, });
      }

    }

    FormData fdTabFolder = new FormData();
    fdTabFolder.left = new FormAttachment(0, 0); // First one in the left top corner
    fdTabFolder.top = new FormAttachment((Control) toolbar.getManagedObject(), 0);
    fdTabFolder.right = new FormAttachment(100, 0);
    fdTabFolder.bottom = new FormAttachment(100, 0);
    tabFolder.setLayoutData(fdTabFolder);

  }

  private void addToolBar() {

    try {
      XulLoader loader = new SwtXulLoader();
      loader.setSettingsManager(XulSpoonSettingsManager.getInstance());
      ResourceBundle bundle = new XulSpoonResourceBundle(Spoon.class);
      XulDomContainer xulDomContainer = loader.loadXul(XUL_FILE_TRANS_GRID_TOOLBAR, bundle);
      xulDomContainer.addEventHandler(this);
      toolbar = (XulToolbar) xulDomContainer.getDocumentRoot().getElementById("nav-toolbar"); //$NON-NLS-1$

      XulToolbarbutton onlyActiveButton = (XulToolbarbutton) toolbar.getElementById("show-inactive"); //$NON-NLS-1$
      if (onlyActiveButton != null) {
        ((ToolItem) onlyActiveButton.getManagedObject()).setImage(GUIResource.getInstance().getImageHideInactive());
      }

      ToolBar swtToolBar = (ToolBar) toolbar.getManagedObject();
      swtToolBar.layout(true, true);
    } catch (Throwable t) {
      log.logError(Const.getStackTracker(t));
      new ErrorDialog(transHistoryComposite.getShell(), BaseMessages.getString(PKG, "Spoon.Exception.ErrorReadingXULFile.Title"), BaseMessages.getString(PKG, "Spoon.Exception.ErrorReadingXULFile.Message", XUL_FILE_TRANS_GRID_TOOLBAR), new Exception(t)); //$NON-NLS-1$ //$NON-NLS-2$
    }
  }

  public void clearLogTable() {
    clearLogTable(tabFolder.getSelectionIndex());
  }

  /**
   * User requested to clear the log table.<br>
   * Better ask confirmation
   */
  public void clearLogTable(int index) {

    LogTableInterface logTable = logTables.get(index);

    if (logTable.isDefined()) {
      String schemaTable = logTable.getQuotedSchemaTableCombination();
      DatabaseMeta databaseMeta = logTable.getDatabaseMeta();

      MessageBox mb = new MessageBox(transGraph.getShell(), SWT.YES | SWT.NO | SWT.ICON_QUESTION);
      mb.setMessage(BaseMessages.getString(PKG, "TransGraph.Dialog.AreYouSureYouWantToRemoveAllLogEntries.Message", schemaTable)); // Nothing found that matches your criteria //$NON-NLS-1$
      mb.setText(BaseMessages.getString(PKG, "TransGraph.Dialog.AreYouSureYouWantToRemoveAllLogEntries.Title")); // Sorry! //$NON-NLS-1$
      if (mb.open() == SWT.YES) {
        Database database = new Database(loggingObject, databaseMeta);
        try {
          database.connect();
          database.truncateTable(schemaTable);
        } catch (Exception e) {
          new ErrorDialog(transGraph.getShell(), BaseMessages.getString(PKG, "TransGraph.Dialog.ErrorClearningLoggingTable.Title"), //$NON-NLS-1$
              BaseMessages.getString(PKG, "TransGraph.Dialog.ErrorClearningLoggingTable.Message"), e); //$NON-NLS-1$
        } finally {
          if (database != null) {
            database.disconnect();
          }

          refreshHistory();
          if (wText.get(index) != null) {
            wText.get(index).setText(""); //$NON-NLS-1$
          }
        }
      }
    }
  }

  public void showHistoryView() {

    if (transHistoryTab == null || transHistoryTab.isDisposed()) {
      addTransHistory();
    } else {
      transHistoryTab.dispose();

      transGraph.checkEmptyExtraView();
    }
  }

  public void replayHistory() {
    int tabIndex = tabFolder.getSelectionIndex();
    int idx = wFields.get(tabIndex).getSelectionIndex();
    if (idx >= 0) {
      String fields[] = wFields.get(tabIndex).getItem(idx);
      String dateString = fields[13];
      Date replayDate = XMLHandler.stringToDate(dateString);
      spoon.executeTransformation(transGraph.getManagedObject(), true, false, false, false, false, replayDate, false);
    }
  }

  /**
   * Background threaded refresh of the history data...
   * 
   */
  public void refreshHistory() {
    new Thread(new Runnable() {
      public void run() {
        if (!gettingHistoryData) {
          gettingHistoryData = true;
          synchronized(rowList){
            rowList.clear();
            for (int i = 0; i < logTables.size(); i++) {
              List<Object[]> rows;
              try {
                rows = getHistoryData(logTables.get(i));
              } catch (Exception e) {
                LogChannel.GENERAL.logError("Unable to get rows of data from logging table "+logTables.get(i), e);
                rows = new ArrayList<Object[]>();
              }
              rowList.add(rows);
            }
          }

          // Signal the refresh timer that there is work...
          //
          displayRefreshNeeded = true;
          gettingHistoryData = false;
        }
      }
    }).start();
  }

  public List<Object[]> getHistoryData(LogTableInterface logTable) throws KettleException {
    // See if there is a transformation loaded that has a connection table specified.
    // 
    if (transMeta != null && !Const.isEmpty(transMeta.getName()) && logTable.isDefined()) {
      DatabaseMeta logConnection = logTable.getDatabaseMeta();

      int maxLines = Props.getInstance().getMaxNrLinesInHistory();
      
      Database database = null;
      try {
        // open a connection
        database = new Database(loggingObject, logConnection);
        database.shareVariablesWith(transMeta);
        database.connect();

        // First, we get the information out of the database table...
        //
        String schemaTable = logTable.getQuotedSchemaTableCombination();

        String sql = "SELECT "; //$NON-NLS-1$
        boolean first = true;
        for (LogTableField field : logTable.getFields()) {
          if (field.isEnabled() && field.isVisible()) {
            if (!first)
              sql += ", "; //$NON-NLS-1$
            first = false;
            sql += logConnection.quoteField(field.getFieldName());
          }
        }
        sql += " FROM " + schemaTable; //$NON-NLS-1$

        RowMetaAndData params = new RowMetaAndData();

        // Do we need to limit the amount of data?
        //
        LogTableField nameField = logTable.getNameField();
        LogTableField keyField = logTable.getKeyField();
        
        if (nameField != null) {
          if (transMeta.isUsingAClusterSchema()) {
              sql += " WHERE " + logConnection.quoteField(nameField.getFieldName()) + " LIKE ?"; //$NON-NLS-1$ //$NON-NLS-2$
              params.addValue(new ValueMeta("transname_literal", ValueMetaInterface.TYPE_STRING), transMeta.getName()); //$NON-NLS-1$
  
              sql += " OR    " + logConnection.quoteField(nameField.getFieldName()) + " LIKE ?"; //$NON-NLS-1$ //$NON-NLS-2$
              params.addValue(new ValueMeta("transname_cluster", ValueMetaInterface.TYPE_STRING), transMeta.getName() + " (%"); //$NON-NLS-1$ //$NON-NLS-2$
          } else {
            sql += " WHERE " + logConnection.quoteField(nameField.getFieldName()) + " = ?"; //$NON-NLS-1$ //$NON-NLS-2$
            params.addValue(new ValueMeta("transname_literal", ValueMetaInterface.TYPE_STRING), transMeta.getName()); //$NON-NLS-1$
            
          }
        } else if (logTable instanceof ChannelLogTable){

          // Set a reasonable default on this until we find a more convenient way to limit this large block of data.
          if (maxLines<=0) {
            maxLines=250;
          }
          
        }

        if (keyField != null && keyField.isEnabled()) {
          sql += " ORDER BY " + logConnection.quoteField(keyField.getFieldName()) + " DESC"; //$NON-NLS-1$//$NON-NLS-2$
        }

        ResultSet resultSet = database.openQuery(sql, params.getRowMeta(), params.getData());

        List<Object[]> rows = new ArrayList<Object[]>();
        Object[] rowData = database.getRow(resultSet);
        while (rowData != null) {
          rows.add(rowData);
          if (rowList.size() < maxLines || maxLines <= 0) {
            rowData = database.getRow(resultSet);
          } else {
            break;
          }
        }
        database.closeQuery(resultSet);

        return rows;
      } catch (Exception e) {
        throw new KettleException("Error retrieveing log records for log table '" + logTable.getLogTableType(), e); //$NON-NLS-1$
      } finally {
        if (database != null)
          database.disconnect();
      }
    } else {
      return new ArrayList<Object[]>();
    }
  }

  public void displayHistoryData(LogTableInterface logTable, int tabIndex, List<Object[]> rows) {
    ColumnInfo[] colinf = columns.get(tabIndex);

    // Now, we're going to display the data in the table view
    //
    if (tabIndex>=wFields.size() || wFields.get(tabIndex).isDisposed()) {
      return;
    }

    int selectionIndex = wFields.get(tabIndex).getSelectionIndex();

    wFields.get(tabIndex).table.clearAll();

    if (rows != null && rows.size() > 0) {
      // OK, now that we have a series of rows, we can add them to the table view...
      // 
      for (int i = 0; i < rows.size(); i++) {
        Object[] rowData = rows.get(i);

        TableItem item = new TableItem(wFields.get(tabIndex).table, SWT.NONE);

        for (int c = 0; c < colinf.length; c++) {

          ColumnInfo column = colinf[c];

          ValueMetaInterface valueMeta = column.getValueMeta();
          String string = null;
          try {
            string = valueMeta.getString(rowData[c]);
          } catch (KettleValueException e) {
            log.logError("history data conversion issue", e); //$NON-NLS-1$
          }
          item.setText(c + 1, Const.NVL(string, "")); //$NON-NLS-1$
        }

        // Add some color
        //
        Long errors = null;
        LogStatus status = null;

        LogTableField errorsField = logTable.getErrorsField();
        if (errorsField != null) {
          int index = logTableFields.get(tabIndex).indexOf(errorsField);
          try {
            errors = colinf[index].getValueMeta().getInteger(rowData[index]);
          } catch (KettleValueException e) {
            log.logError("history data conversion issue", e); //$NON-NLS-1$
          }
        }
        LogTableField statusField = logTable.getStatusField();
        if (statusField != null) {
          int index = logTableFields.get(tabIndex).indexOf(statusField);
          String statusString = null;
          try {
            statusString = colinf[index].getValueMeta().getString(rowData[index]);
          } catch (KettleValueException e) {
            log.logError("history data conversion issue", e); //$NON-NLS-1$
          }
          if (statusString != null) {
            status = LogStatus.findStatus(statusString);
          }
        }

        if (errors != null && errors.longValue() > 0L) {
          item.setBackground(GUIResource.getInstance().getColorRed());
        } else if (status != null && LogStatus.STOP.equals(status)) {
          item.setBackground(GUIResource.getInstance().getColorYellow());
        }
      }

      wFields.get(tabIndex).removeEmptyRows();
      wFields.get(tabIndex).setRowNums();
      wFields.get(tabIndex).optWidth(true);
    } else {
      wFields.get(tabIndex).clearAll(false);
      // new TableItem(wFields.get(tabIndex).table, SWT.NONE); // Give it an item to prevent errors on various platforms.
    }

    if (selectionIndex >= 0 && selectionIndex < wFields.get(tabIndex).getItemCount()) {
      wFields.get(tabIndex).table.select(selectionIndex);
      showLogEntry();
    }
  }

  public void showLogEntry() {
    int tabIndex = tabFolder.getSelectionIndex();
    LogTableInterface logTable = logTables.get(tabIndex);
    List<LogTableField> fields = logTableFields.get(tabIndex);

    Text text = wText.get(tabIndex);

    if (text == null || text.isDisposed())
      return;

    List<Object[]> list = rowList.get(tabIndex);

    if (list == null || list.size() == 0) {
      String message;
      if (logTable.isDefined()) {
        message = BaseMessages.getString(PKG, "TransHistory.PleaseRefresh.Message"); //$NON-NLS-1$
      } else {
        message = BaseMessages.getString(PKG, "TransHistory.HistoryConfiguration.Message"); //$NON-NLS-1$
      }
      text.setText(message);
      return;
    }

    // grab the selected line in the table:
    int nr = wFields.get(tabIndex).table.getSelectionIndex();
    if (nr >= 0 && list != null && nr < list.size()) {
      // OK, grab this one from the buffer...
      Object[] row = list.get(nr);

      // What is the name of the log field?
      //
      LogTableField logField = logTables.get(tabIndex).getLogField();
      if (logField != null) {
        int index = fields.indexOf(logField);
        if (index>=0) {
          String logText = row[index].toString();
  
          text.setText(Const.NVL(logText, "")); //$NON-NLS-1$
  
          text.setSelection(text.getText().length());
          text.showSelection();
        } else {
          text.setText(BaseMessages.getString(PKG, "TransHistory.HistoryConfiguration.NoLoggingFieldDefined"));
        }
      }
    }
  }

  public void refreshHistoryIfNeeded() {
    boolean reallyRefresh = false;
    synchronized (refreshNeededLock) {
      reallyRefresh = refreshNeeded;
      refreshNeeded = false;
    }

    if (reallyRefresh) {
      refreshHistory();
    }
  }

  public void markRefreshNeeded() {
    synchronized (refreshNeededLock) {
      refreshNeeded = true;
    }
  }

  /**
   * @return the transHistoryTab
   */
  public CTabItem getTransHistoryTab() {
    return transHistoryTab;
  }

  /* (non-Javadoc)
   * @see org.pentaho.ui.xul.impl.XulEventHandler#getData()
   */
  public Object getData() {
    return null;
  }

  /* (non-Javadoc)
   * @see org.pentaho.ui.xul.impl.XulEventHandler#getName()
   */
  public String getName() {
    return "transhistory"; //$NON-NLS-1$
  }

  /* (non-Javadoc)
   * @see org.pentaho.ui.xul.impl.XulEventHandler#getXulDomContainer()
   */
  public XulDomContainer getXulDomContainer() {
    return null;
  }

  /* (non-Javadoc)
   * @see org.pentaho.ui.xul.impl.XulEventHandler#setData(java.lang.Object)
   */
  public void setData(Object data) {
  }

  /* (non-Javadoc)
   * @see org.pentaho.ui.xul.impl.XulEventHandler#setName(java.lang.String)
   */
  public void setName(String name) {
  }

  /* (non-Javadoc)
   * @see org.pentaho.ui.xul.impl.XulEventHandler#setXulDomContainer(org.pentaho.ui.xul.XulDomContainer)
   */
  public void setXulDomContainer(XulDomContainer xulDomContainer) {
  }

}
