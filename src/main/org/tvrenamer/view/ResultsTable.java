package org.tvrenamer.view;

import static org.tvrenamer.model.util.Constants.*;
import static org.tvrenamer.view.UIUtils.showMessageBox;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.TaskBar;
import org.eclipse.swt.widgets.TaskItem;

import org.tvrenamer.controller.AddEpisodeListener;
import org.tvrenamer.controller.FileMover;
import org.tvrenamer.controller.MoveRunner;
import org.tvrenamer.controller.ShowInformationListener;
import org.tvrenamer.controller.ShowListingsListener;
import org.tvrenamer.controller.UpdateChecker;
import org.tvrenamer.controller.UrlLauncher;
import org.tvrenamer.controller.util.StringUtils;
import org.tvrenamer.model.EpisodeDb;
import org.tvrenamer.model.FailedShow;
import org.tvrenamer.model.FileEpisode;
import org.tvrenamer.model.SWTMessageBoxType;
import org.tvrenamer.model.Series;
import org.tvrenamer.model.Show;
import org.tvrenamer.model.ShowStore;
import org.tvrenamer.model.UserPreference;
import org.tvrenamer.model.UserPreferences;
import org.tvrenamer.model.util.Environment;

import java.text.Collator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Observable;
import java.util.Observer;
import java.util.Queue;
import java.util.logging.Logger;

public final class ResultsTable implements Observer, AddEpisodeListener {
    private static final Logger logger = Logger.getLogger(ResultsTable.class.getName());
    private static final Collator COLLATOR = Collator.getInstance(Locale.getDefault());

    private static final int SELECTED_COLUMN = 0;
    private static final int CURRENT_FILE_COLUMN = 1;
    private static final int NEW_FILENAME_COLUMN = 2;
    private static final int STATUS_COLUMN = 3;
    private static final int ITEM_NOT_IN_TABLE = -1;

    private Shell shell;
    private Display display;
    private List<String> ignoreKeywords;

    private Button addFilesButton;
    private Button addFolderButton;
    private Button clearFilesButton;
    private Button renameSelectedButton;
    private Table resultsTable;
    private ProgressBar totalProgressBar;
    private TaskItem taskItem = null;

    private UserPreferences prefs;
    private boolean apiDeprecated = false;

    private final EpisodeDb episodeMap = new EpisodeDb();

    private void setupUpdateStuff(final Composite parentComposite) {
        Link updatesAvailableLink = new Link(parentComposite, SWT.VERTICAL);
        // updatesAvailableLink.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, true));
        updatesAvailableLink.setVisible(false);
        updatesAvailableLink.setText(UPDATE_AVAILABLE);
        updatesAvailableLink.addSelectionListener(new UrlLauncher(TVRENAMER_DOWNLOAD_URL));

        // Show the label if updates are available (in a new thread)
        UpdateChecker.notifyOfUpdate(updateIsAvailable -> {
            if (updateIsAvailable) {
                display.asyncExec(() -> updatesAvailableLink.setVisible(true));
            }
        });
    }

    private void setupMainWindow() {
        final Composite topButtonsComposite = new Composite(shell, SWT.FILL);
        topButtonsComposite.setLayout(new RowLayout());

        addFilesButton = new Button(topButtonsComposite, SWT.PUSH);
        addFilesButton.setText("Add files");

        addFolderButton = new Button(topButtonsComposite, SWT.PUSH);
        addFolderButton.setText("Add Folder");

        clearFilesButton = new Button(topButtonsComposite, SWT.PUSH);
        clearFilesButton.setText("Clear List");

        setupUpdateStuff(topButtonsComposite);
        setupResultsTable();
        setupTableDragDrop();

        Composite bottomButtonsComposite = new Composite(shell, SWT.FILL);
        bottomButtonsComposite.setLayout(new GridLayout(3, false));
        GridData bottomButtonsCompositeGridData = new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1);
        bottomButtonsComposite.setLayoutData(bottomButtonsCompositeGridData);

        final Button quitButton = new Button(bottomButtonsComposite, SWT.PUSH);
        GridData quitButtonGridData = new GridData(GridData.BEGINNING, GridData.CENTER, false, false);
        quitButtonGridData.minimumWidth = 70;
        quitButtonGridData.widthHint = 70;
        quitButton.setLayoutData(quitButtonGridData);
        quitButton.setText(QUIT_LABEL);

        totalProgressBar = new ProgressBar(bottomButtonsComposite, SWT.SMOOTH);
        totalProgressBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, true));

        TaskBar taskBar = display.getSystemTaskBar();
        if (taskBar != null) {
            taskItem = taskBar.getItem(shell);
            if (taskItem == null) {
                taskItem = taskBar.getItem(null);
            }
        }

        renameSelectedButton = new Button(bottomButtonsComposite, SWT.PUSH);
        GridData renameSelectedButtonGridData = new GridData(GridData.END, GridData.CENTER, false, false);
        renameSelectedButton.setLayoutData(renameSelectedButtonGridData);

        setRenameButtonText(renameSelectedButton);

        renameSelectedButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                renameFiles();
            }
        });

        quitButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                uiCleanup();
            }
        });
    }

    private void makeMenuItem(Menu parent, String text, Listener listener, char shortcut) {
        MenuItem newItem = new MenuItem(parent, SWT.PUSH);
        newItem.setText(text + "\tCtrl+" + shortcut);
        newItem.addListener(SWT.Selection, listener);
        newItem.setAccelerator(SWT.CONTROL | shortcut);
    }

    private void setupMenuBar() {
        Menu menuBarMenu = new Menu(shell, SWT.BAR);
        Menu helpMenu;

        Listener preferencesListener = e -> showPreferencesPane();
        Listener aboutListener = e -> showAboutPane();
        Listener quitListener = e -> uiCleanup();

        if (Environment.IS_MAC_OSX) {
            // Add the special Mac OSX Preferences, About and Quit menus.
            CocoaUIEnhancer enhancer = new CocoaUIEnhancer(APPLICATION_NAME);
            enhancer.hookApplicationMenu(display, quitListener, aboutListener, preferencesListener);

            setupHelpMenuBar(menuBarMenu);
        } else {
            // Add the normal Preferences, About and Quit menus.
            MenuItem fileMenuItem = new MenuItem(menuBarMenu, SWT.CASCADE);
            fileMenuItem.setText("File");

            Menu fileMenu = new Menu(shell, SWT.DROP_DOWN);
            fileMenuItem.setMenu(fileMenu);

            makeMenuItem(fileMenu, PREFERENCES_LABEL, preferencesListener, 'P');
            makeMenuItem(fileMenu, EXIT_LABEL, quitListener, 'Q');

            helpMenu = setupHelpMenuBar(menuBarMenu);

            // The About item is added to the OSX bar, so we need to add it manually here
            MenuItem helpAboutItem = new MenuItem(helpMenu, SWT.PUSH);
            helpAboutItem.setText("About");
            helpAboutItem.addListener(SWT.Selection, aboutListener);
        }

        shell.setMenuBar(menuBarMenu);
    }

    private Menu setupHelpMenuBar(Menu menuBar) {
        MenuItem helpMenuHeader = new MenuItem(menuBar, SWT.CASCADE);
        helpMenuHeader.setText("Help");

        Menu helpMenu = new Menu(shell, SWT.DROP_DOWN);
        helpMenuHeader.setMenu(helpMenu);

        MenuItem helpHelpItem = new MenuItem(helpMenu, SWT.PUSH);
        helpHelpItem.setText("Help");

        MenuItem helpVisitWebPageItem = new MenuItem(helpMenu, SWT.PUSH);
        helpVisitWebPageItem.setText("Visit Web Page");
        helpVisitWebPageItem.addSelectionListener(new UrlLauncher(TVRENAMER_PROJECT_URL));

        return helpMenu;
    }

    private void setupAddFilesDialog() {
        final FileDialog fd = new FileDialog(shell, SWT.MULTI);
        addFilesButton.addSelectionListener(new SelectionAdapter() {

            @Override
            public void widgetSelected(SelectionEvent e) {
                String pathPrefix = fd.open();
                if (pathPrefix != null) {
                    episodeMap.addFilesToQueue(pathPrefix, fd.getFileNames());
                }
            }
        });

        final DirectoryDialog dd = new DirectoryDialog(shell, SWT.SINGLE);
        addFolderButton.addSelectionListener(new SelectionAdapter() {

            @Override
            public void widgetSelected(SelectionEvent e) {
                String directory = dd.open();
                if (directory != null) {
                    // load all of the files in the dir
                    episodeMap.addFolderToQueue(directory);
                }
            }

        });
    }

    private void setupClearFilesButton() {
        clearFilesButton.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                deleteAllTableItems();
            }
        });
    }

    private void setupSelectionListener() {
        resultsTable.addListener(SWT.Selection, event -> {
            if (event.detail == SWT.CHECK) {
                TableItem eventItem = (TableItem) event.item;
                // This assumes that the current status of the TableItem
                // already reflects its toggled state, which appears to
                // be the case.
                boolean checked = eventItem.getChecked();
                boolean isSelected = false;

                for (final TableItem item : resultsTable.getSelection()) {
                    if (item == eventItem) {
                        isSelected = true;
                        break;
                    }
                }
                if (isSelected) {
                    for (final TableItem item : resultsTable.getSelection()) {
                        item.setChecked(checked);
                    }
                } else {
                    resultsTable.deselectAll();
                }
            }
            // else, it's a SELECTED event, which we just don't care about
        });
    }

    private void setupResultsTable() {
        resultsTable = new Table(shell, SWT.CHECK | SWT.FULL_SELECTION | SWT.MULTI);
        resultsTable.setHeaderVisible(true);
        resultsTable.setLinesVisible(true);
        GridData gridData = new GridData(GridData.FILL_BOTH);
        // gridData.widthHint = 780;
        gridData.heightHint = 350;
        gridData.horizontalSpan = 3;
        resultsTable.setLayoutData(gridData);

        final TableColumn selectedColumn = new TableColumn(resultsTable, SWT.LEFT);
        selectedColumn.setText("Selected");
        selectedColumn.setWidth(60);

        final TableColumn sourceColumn = new TableColumn(resultsTable, SWT.LEFT);
        sourceColumn.setText("Current File");
        sourceColumn.setWidth(550);

        final TableColumn destinationColumn = new TableColumn(resultsTable, SWT.LEFT);
        setColumnDestText(destinationColumn);
        destinationColumn.setWidth(550);

        final TableColumn statusColumn = new TableColumn(resultsTable, SWT.LEFT);
        statusColumn.setText("Status");
        statusColumn.setWidth(60);

        // Allow deleting of elements
        resultsTable.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                super.keyReleased(e);

                switch (e.keyCode) {

                    // backspace
                    case '\u0008':
                    // delete
                    case '\u007F':
                        deleteSelectedTableItems();
                        break;

                    // Code analysis says have a default clause...
                    default:
                }

            }
        });

        selectedColumn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                sortTable(selectedColumn, SELECTED_COLUMN);
            }
        });

        sourceColumn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                sortTable(sourceColumn, CURRENT_FILE_COLUMN);
            }
        });

        destinationColumn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                sortTable(destinationColumn, NEW_FILENAME_COLUMN);
            }
        });

        statusColumn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                sortTable(statusColumn, STATUS_COLUMN);
            }
        });

        // editable table
        final TableEditor editor = new TableEditor(resultsTable);
        editor.horizontalAlignment = SWT.CENTER;
        editor.grabHorizontal = true;

        setupSelectionListener();
    }

    private void setupTableDragDrop() {
        DropTarget dt = new DropTarget(resultsTable, DND.DROP_DEFAULT | DND.DROP_MOVE);
        dt.setTransfer(new Transfer[] { FileTransfer.getInstance() });
        dt.addDropListener(new DropTargetAdapter() {

            @Override
            public void drop(DropTargetEvent e) {
                FileTransfer ft = FileTransfer.getInstance();
                if (ft.isSupportedType(e.currentDataType)) {
                    String[] fileList = (String[]) e.data;
                    episodeMap.addArrayOfStringsToQueue(fileList);
                }
            }
        });
    }

    Display getDisplay() {
        return display;
    }

    ProgressBar getProgressBar() {
        return totalProgressBar;
    }

    TaskItem getTaskItem() {
        return taskItem;
    }

    /**
     * Fill in the value for the "Proposed File" column of the given row, with the text
     * we get from the given episode.  This is the only method that should ever set
     * this text, to ensure that the text of each row is ALWAYS the value returned by
     * getReplacementText() on the associated episode.
     *
     * @param item
     *    the row in the table to set the text of the "Proposed File" column
     * @param ep
     *    the FileEpisode to use to obtain the text
     */
    private void setProposedDestColumn(final TableItem item, final FileEpisode ep) {
        item.setText(NEW_FILENAME_COLUMN, ep.getReplacementText());
    }

    private void listingsDownloaded(TableItem item, FileEpisode episode) {
        boolean epFound = episode.listingsComplete();
        display.asyncExec(() -> {
            if (tableContainsTableItem(item)) {
                setProposedDestColumn(item, episode);
                if (epFound) {
                    item.setImage(STATUS_COLUMN, FileMoveIcon.ADDED.icon);
                    item.setChecked(true);
                } else {
                    item.setImage(STATUS_COLUMN, FileMoveIcon.FAIL.icon);
                    item.setChecked(false);
                }
            }
        });
    }

    private void listingsFailed(TableItem item, FileEpisode episode, Exception err) {
        episode.listingsFailed(err);
        display.asyncExec(() -> {
            if (tableContainsTableItem(item)) {
                setProposedDestColumn(item, episode);
                item.setImage(STATUS_COLUMN, FileMoveIcon.FAIL.icon);
                item.setChecked(false);
            }
        });
    }

    private void getSeriesListings(Series series, TableItem item, FileEpisode episode) {
        series.addListingsListener(new ShowListingsListener() {
                @Override
                public void listingsDownloadComplete() {
                    listingsDownloaded(item, episode);
                }

                @Override
                public void listingsDownloadFailed(Exception err) {
                    listingsFailed(item, episode, err);
                }
            });
    }


    private void tableItemDownloaded(TableItem item, FileEpisode episode) {
        display.asyncExec(() -> {
            if (tableContainsTableItem(item)) {
                setProposedDestColumn(item, episode);
                item.setImage(STATUS_COLUMN, FileMoveIcon.ADDED.icon);
            }
        });
    }

    private void tableItemFailed(TableItem item, FileEpisode episode) {
        display.asyncExec(() -> {
            if (tableContainsTableItem(item)) {
                setProposedDestColumn(item, episode);
                item.setImage(STATUS_COLUMN, FileMoveIcon.FAIL.icon);
                item.setChecked(false);
            }
        });
    }

    private synchronized void noteApiFailure() {
        boolean showDialogBox = !apiDeprecated;
        apiDeprecated = true;
        if (showDialogBox) {
            boolean updateIsAvailable = UpdateChecker.isUpdateAvailable();
            showMessageBox(SWTMessageBoxType.ERROR, ERROR_LABEL,
                           updateIsAvailable ? GET_UPDATE_MESSAGE : NEED_UPDATE);
        }
    }

    @Override
    public void addEpisodes(Queue<FileEpisode> episodes) {
        // Update the list of ignored keywords
        ignoreKeywords = prefs.getIgnoreKeywords();

        for (final FileEpisode episode : episodes) {
            final String fileName = episode.getFilepath();
            final TableItem item = createTableItem(resultsTable, fileName, episode);
            synchronized (this) {
                if (apiDeprecated) {
                    tableItemFailed(item, episode);
                    continue;
                }
            }

            final String showName = episode.getFilenameShow();
            ShowStore.getShow(showName, new ShowInformationListener() {
                    @Override
                    public void downloadSucceeded(Show show) {
                        episode.setEpisodeShow(show);
                        tableItemDownloaded(item, episode);
                        if (show.isValidSeries()) {
                            getSeriesListings(show.asSeries(), item, episode);
                        }
                    }

                    @Override
                    public void downloadFailed(FailedShow failedShow) {
                        // We don't send a FailedShow to the FileEpisode
                        episode.setEpisodeShow(null);
                        tableItemFailed(item, episode);
                    }

                    @Override
                    public void apiHasBeenDeprecated() {
                        noteApiFailure();
                        tableItemFailed(item, episode);
                    }
                });
        }
    }

    private int getTableItemIndex(TableItem item) {
        try {
            return resultsTable.indexOf(item);
        } catch (IllegalArgumentException | SWTException ignored) {
            // We'll just fall through and return the sentinel.
        }
        return ITEM_NOT_IN_TABLE;
    }

    private boolean tableContainsTableItem(TableItem item) {
        return (ITEM_NOT_IN_TABLE != getTableItemIndex(item));
    }

    public Label getProgressLabel(TableItem item) {
        Label progressLabel = new Label(resultsTable, SWT.SHADOW_NONE | SWT.CENTER);
        TableEditor editor = new TableEditor(resultsTable);
        editor.grabHorizontal = true;
        editor.setEditor(progressLabel, item, STATUS_COLUMN);

        return progressLabel;
    }

    private void renameFiles() {
        if (!prefs.isMoveEnabled() && !prefs.isRenameEnabled()) {
            logger.info("move and rename both disabled, nothing to be done.");
            return;
        }

        final List<FileMover> pendingMoves = new LinkedList<>();
        for (final TableItem item : resultsTable.getItems()) {
            if (item.getChecked()) {
                String fileName = item.getText(CURRENT_FILE_COLUMN);
                final FileEpisode episode = episodeMap.get(fileName);
                // Skip files not successfully downloaded and ready to be moved
                if (!episode.isReady()) {
                    logger.info("selected but not ready: " + episode.getFilepath());
                    continue;
                }
                FileMover pendingMove = new FileMover(episode);
                pendingMove.addObserver(new FileCopyMonitor(this, item));
                pendingMoves.add(pendingMove);
            }
        }

        MoveRunner mover = new MoveRunner(pendingMoves);
        mover.setUpdater(new ProgressBarUpdater(this));
        mover.runThread();
    }

    private TableItem createTableItem(Table tblResults, String fileName, FileEpisode episode) {
        TableItem item = new TableItem(tblResults, SWT.NONE);

        // Set if the item is checked or not according to a list of banned keywords
        item.setChecked(!isNameIgnored(fileName));
        item.setText(CURRENT_FILE_COLUMN, fileName);
        setProposedDestColumn(item, episode);
        item.setImage(STATUS_COLUMN, FileMoveIcon.DOWNLOADING.icon);
        return item;
    }

    private boolean isNameIgnored(String fileName) {
        for (String ignoreKeyword : ignoreKeywords) {
            if (StringUtils.toLower(fileName).contains(ignoreKeyword)) {
                return true;
            }
        }
        return false;
    }

    private void deleteTableItem(final TableItem item) {
        episodeMap.remove(item.getText(CURRENT_FILE_COLUMN));
        item.dispose();
    }

    private void deleteSelectedTableItems() {
        for (final TableItem item : resultsTable.getSelection()) {
            int index = getTableItemIndex(item);
            deleteTableItem(item);

            if (ITEM_NOT_IN_TABLE == index) {
                logger.info("error: somehow selected item not found in table");
            }
        }
        resultsTable.deselectAll();
    }

    private void deleteAllTableItems() {
        for (final TableItem item : resultsTable.getItems()) {
            deleteTableItem(item);
        }
    }

    /**
     * Insert a copy of the row at the given position, and then delete the original row.
     * Note that insertion does not overwrite the row that is already there.  It pushes
     * the row, and every row below it, down one slot.
     *
     * @param oldItem
     *   the TableItem to copy
     * @param positionToInsert
     *   the position where we should insert the row
     */
    private void setSortedItem(final TableItem oldItem, final int positionToInsert) {
        boolean wasChecked = oldItem.getChecked();
        int oldStyle = oldItem.getStyle();

        TableItem item = new TableItem(resultsTable, oldStyle, positionToInsert);
        item.setChecked(wasChecked);
        item.setText(CURRENT_FILE_COLUMN, oldItem.getText(CURRENT_FILE_COLUMN));
        item.setText(NEW_FILENAME_COLUMN, oldItem.getText(NEW_FILENAME_COLUMN));
        item.setImage(STATUS_COLUMN, oldItem.getImage(STATUS_COLUMN));

        oldItem.dispose();
    }

    private static String getItemTextValue(final TableItem item, final int column) {
        switch (column) {
            case SELECTED_COLUMN:
                return (item.getChecked()) ? "1" : "0";
            case STATUS_COLUMN:
                // Sorting alphabetically by the status icon's filename is pretty random.
                // I don't think there is any real ordering for a status; sorting based
                // on this column makes sense simply to group together items of the
                // same status.  I don't think it matters what order they're in.
                return item.getImage(column).toString();
            default:
                return item.getText(column);
        }
    }

    private void sortTable(TableColumn column, int columnNum) {
        int sortDirection = resultsTable.getSortDirection() == SWT.DOWN ? SWT.UP : SWT.DOWN;
        // Get the items
        TableItem[] items = resultsTable.getItems();

        // Go through the item list and bubble rows up to the top as appropriate
        for (int i = 1; i < items.length; i++) {
            String value1 = getItemTextValue(items[i], columnNum);
            for (int j = 0; j < i; j++) {
                String value2 = getItemTextValue(items[j], columnNum);
                // Compare the two values and order accordingly
                int comparison = COLLATOR.compare(value1, value2);
                if (((comparison < 0) && (sortDirection == SWT.DOWN))
                    || (comparison > 0) && (sortDirection == SWT.UP))
                {
                    // Insert a copy of row i at position j, and then delete
                    // row i.  Then fetch the list of items anew, since we
                    // just modified it.
                    setSortedItem(items[i], j);
                    items = resultsTable.getItems();
                    break;
                }
            }
        }
        resultsTable.setSortDirection(sortDirection);
        resultsTable.setSortColumn(column);
    }

    void refreshTable() {
        logger.info("Refreshing table");
        for (TableItem item : resultsTable.getItems()) {
            String fileName = item.getText(CURRENT_FILE_COLUMN);
            FileEpisode episode = episodeMap.remove(fileName);
            String newFileName = episode.getFilepath();
            episodeMap.put(newFileName, episode);
            item.setText(CURRENT_FILE_COLUMN, newFileName);
            setProposedDestColumn(item, episode);
        }
    }

    private void setRenameButtonText(Button b) {
        String label = RENAME_LABEL;
        String tooltip = RENAME_TOOLTIP;

        if (prefs.isMoveEnabled()) {
            tooltip = INTRO_MOVE_DIR + prefs.getDestinationDirectoryName()
                + FINISH_MOVE_DIR;
            if (prefs.isRenameEnabled()) {
                label = RENAME_AND_MOVE;
                tooltip = MOVE_INTRO + AND_RENAME + tooltip;
            } else {
                label = JUST_MOVE_LABEL;
                tooltip = MOVE_INTRO + tooltip;
            }
        } else if (!prefs.isRenameEnabled()) {
            // This setting, "do not move and do not rename", really makes no sense.
            // But for now, we're not taking the effort to explicitly disable it.
            tooltip = NO_ACTION_TOOLTIP;
        }

        b.setText(label);
        b.setToolTipText(tooltip);
        shell.changed(new Control[] {b});
        shell.layout(false, true);
    }

    private void setColumnDestText(final TableColumn destinationColumn) {
        if (prefs.isMoveEnabled()) {
            destinationColumn.setText(MOVE_HEADER);
        } else {
            destinationColumn.setText(RENAME_HEADER);
        }
    }

    private void updateUserPreferences(UserPreferences observed, UserPreference userPref) {
        logger.info("Preference change event: " + userPref);

        if ((userPref == UserPreference.MOVE_ENABLED)
            || (userPref == UserPreference.RENAME_ENABLED))
        {
            setColumnDestText(resultsTable.getColumn(NEW_FILENAME_COLUMN));
            setRenameButtonText(renameSelectedButton);
        }
        if ((userPref == UserPreference.REPLACEMENT_MASK)
            || (userPref == UserPreference.MOVE_ENABLED)
            || (userPref == UserPreference.RENAME_ENABLED)
            || (userPref == UserPreference.DEST_DIR)
            || (userPref == UserPreference.SEASON_PREFIX)
            || (userPref == UserPreference.LEADING_ZERO))
        {
            refreshTable();
        }

        if (userPref == UserPreference.IGNORE_REGEX) {
            ignoreKeywords = observed.getIgnoreKeywords();
        }

        if (userPref == UserPreference.DEST_DIR) {
            UIUtils.checkDestinationDirectory(observed);
        }
    }

    /* (non-Javadoc)
     * @see java.util.Observer#update(java.util.Observable, java.lang.Object)
     */
    @Override
    public void update(Observable observable, Object value) {
        if (observable instanceof UserPreferences && value instanceof UserPreference) {
            updateUserPreferences((UserPreferences) observable,
                                  (UserPreference) value);
        }
    }

    private void showPreferencesPane() {
        PreferencesDialog preferencesDialog = new PreferencesDialog(shell);
        preferencesDialog.open();
    }

    /**
     * Create the 'About' dialog.
     */
    private void showAboutPane() {
        AboutDialog aboutDialog = new AboutDialog(shell);
        aboutDialog.open();
    }
}
