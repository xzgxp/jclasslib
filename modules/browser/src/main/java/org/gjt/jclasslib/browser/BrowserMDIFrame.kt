/*
    This library is free software; you can redistribute it and/or
    modify it under the terms of the GNU General Public
    License as published by the Free Software Foundation; either
    version 2 of the license, or (at your option) any later version.
*/

package org.gjt.jclasslib.browser

import org.gjt.jclasslib.browser.config.BrowserConfig
import org.gjt.jclasslib.browser.config.classpath.ClasspathArchiveEntry
import org.gjt.jclasslib.browser.config.classpath.ClasspathBrowser
import org.gjt.jclasslib.browser.config.classpath.ClasspathSetupDialog
import org.gjt.jclasslib.browser.config.window.WindowState
import org.gjt.jclasslib.mdi.BasicFileFilter
import org.gjt.jclasslib.mdi.MDIConfig
import org.gjt.jclasslib.structures.InvalidByteCodeException
import org.gjt.jclasslib.util.GUIHelper
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.beans.PropertyVetoException
import java.beans.XMLDecoder
import java.beans.XMLEncoder
import java.io.*
import java.util.prefs.Preferences
import javax.swing.*

class BrowserMDIFrame : JFrame() {

    private val desktopManager: BrowserDesktopManager = BrowserDesktopManager(this)
    //TODO move to BrowserDesktopManager
    val desktopPane = JDesktopPane().apply {
        background = Color.LIGHT_GRAY
        this.desktopManager = desktopManager

    }

    val actionOpenClassFile: Action = DefaultAction("Open class file", "Open a class file", "open_small.png", "open_large.png") {
        val result = classesFileChooser.showOpenDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) {
            repaintNow()
            withWaitCursor {
                classesChooserPath = classesFileChooser.currentDirectory.absolutePath
                val file = classesFileChooser.selectedFile
                if (file.path.toLowerCase().endsWith(".class")) {
                    openClassFromFile(file)
                } else {
                    openClassFromJar(file)
                }?.apply {
                    try {
                        setMaximum(true)
                    } catch (ex: PropertyVetoException) {
                    }
                }
            }
        }
    }

    val actionBrowseClasspath : Action = DefaultAction("Browse classpath", "Browse the current classpath to open a class file", "tree_small.png", "tree_large.png") {
        classpathBrowser.isVisible = true
        val selectedClassName = classpathBrowser.selectedClassName
        if (selectedClassName != null) {
            val findResult = config.findClass(selectedClassName)
            if (findResult != null) {
                repaintNow()
                withWaitCursor {
                    val frame = BrowserInternalFrame(desktopManager, WindowState(findResult.fileName))
                    try {
                        frame.setMaximum(true)
                    } catch (ex: PropertyVetoException) {
                    }
                }
            } else {
                GUIHelper.showMessage(this, "Error loading " + selectedClassName, JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    val actionSetupClasspath: Action = DefaultAction("Setup classpath", "Configure the classpath") {
        classpathSetupDialog.isVisible = true
    }

    val actionNewWorkspace: Action = DefaultAction("New workspace", "Close all frames and open a new workspace") {
        closeAllFrames()
        workspaceFile = null
        config = browserConfigWithRuntimeLib()
        updateTitle()

    }

    val actionOpenWorkspace: Action = DefaultAction("Open workspace", "Open workspace from disk", "open_ws_small.png", "open_ws_large.png") {
        val result = workspaceFileChooser.showOpenDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) {
            val selectedFile = workspaceFileChooser.selectedFile
            openWorkspace(selectedFile)
            workspaceChooserPath = workspaceFileChooser.currentDirectory.absolutePath
        }
    }

    val actionSaveWorkspace: Action = DefaultAction("Save workspace", "Save current workspace to disk", "save_ws_small.png", "save_ws_large.png") {
        saveWorkspace()
    }

    val actionSaveWorkspaceAs: Action = DefaultAction("Save workspace as", "Save current workspace to a different file") {
        val workspaceFile = this.workspaceFile
        if (workspaceFile != null) {
            saveWorkspaceToFile(workspaceFile)
        } else {
            saveWorkspace()
        }

    }.apply { isEnabled = false }


    val actionQuit = DefaultAction("Quit") {
        saveSettings()
        saveWindowSettings()
        dispose()
        System.exit(0)
    }

    val actionBackward: Action = DefaultAction("Backward", "Move backward in the navigation history", "browser_backward_small.png", "browser_backward_large.png") {
        getSelectedInternalFrame()?.browserComponent?.history?.historyBackward()
    }.apply { isEnabled = false }

    val actionForward: Action = DefaultAction("Forward", "Move backward in the navigation history", "browser_forward_small.png", "browser_forward_large.png") {
        getSelectedInternalFrame()?.browserComponent?.history?.historyForward()
    }.apply { isEnabled = false }

    var actionReload: Action = DefaultAction("Reload", "Reload class file", "reload_small.png", "reload_large.png") {
        try {
            getSelectedInternalFrame()?.reload()
        } catch (e: IOException) {
            GUIHelper.showMessage(this, e.message, JOptionPane.ERROR_MESSAGE)
        }

    }.apply { isEnabled = false }

    val actionShowHomepage: Action = DefaultAction("jclasslib on the web", "Visit jclasslib on the web", "web_small.png", "web_large.png") {
        GUIHelper.showURL("http://www.ej-technologies.com/products/jclasslib/overview.html")
    }

    val actionShowEJT: Action = DefaultAction("ej-technologies on the web", "Visit ej-technologies on the web", "web_small.png") {
        GUIHelper.showURL("http://www.ej-technologies.com")
    }

    val actionShowHelp: Action = DefaultAction("Show help", "Show the jclasslib documentation", "help.png") {
        GUIHelper.showURL(File("doc/help.html").canonicalFile.toURI().toURL().toExternalForm())
    }

    val actionAbout: Action = DefaultAction("About the jclasslib bytecode viewer", "Show the jclasslib documentation") {
        BrowserAboutDialog(this).isVisible = true
    }

    val previousWindowAction: Action = DefaultAction("Previous window", "Cycle to the previous opened window") {
        desktopManager.cycleToPreviousWindow()
    }.apply { isEnabled = false }

    val nextWindowAction: Action = DefaultAction("Next window", "Cycle to the next opened window") {
        desktopManager.cycleToNextWindow()
    }.apply { isEnabled = false }

    val tileWindowsAction: Action = DefaultAction("Tile windows", "Tile all windows in the main frame") {
        desktopManager.tileWindows()
    }.apply { isEnabled = false }

    val stackWindowsAction: Action = DefaultAction("Stack windows", "Stack all windows in the main frame") {
        desktopManager.stackWindows()
    }.apply { isEnabled = false }


    val menuWindow = JMenu("Window").apply {
        add(previousWindowAction).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F2, InputEvent.CTRL_MASK)
        }
        add(nextWindowAction).apply {
            accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.CTRL_MASK)
        }
        add(tileWindowsAction)
        add(stackWindowsAction)
    }

    private var lastNormalFrameBounds: Rectangle? = null

    private var workspaceFile: File? = null
    private var workspaceChooserPath = ""
    var classesChooserPath = ""
    var config: BrowserConfig = browserConfigWithRuntimeLib()
        private set


    private val workspaceFileChooser: JFileChooser by lazy {
        JFileChooser(workspaceChooserPath).apply {
            dialogTitle = "Choose workspace file"
            fileFilter = BasicFileFilter(WORKSPACE_FILE_SUFFIX, "jclasslib workspace files")
        }
    }

    private val classesFileChooser: JFileChooser by lazy {
        JFileChooser(workspaceChooserPath).apply {
            dialogTitle = "Choose class file or jar file"
            addChoosableFileFilter(BasicFileFilter("class", "class files"))
            addChoosableFileFilter(BasicFileFilter("jar", "jar files"))
            fileFilter = BasicFileFilter(arrayOf("class", "jar"), "class files and jar files")
        }
    }

    private val recentMenu: RecentMenu = RecentMenu(this)
    private val classpathSetupDialog: ClasspathSetupDialog by lazy { ClasspathSetupDialog(this) }
    private val classpathBrowser: ClasspathBrowser by lazy { ClasspathBrowser(this, "Configured classpath:", true) }
    private val jarBrowser: ClasspathBrowser by lazy { ClasspathBrowser(this, "Classes in selected JAR file:", false) }

    private fun browserConfigWithRuntimeLib() = BrowserConfig().apply { addRuntimeLib() }

    private fun getSelectedInternalFrame() = desktopPane.selectedFrame as BrowserInternalFrame?

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (visible) {
            desktopManager.showAll()
        }
    }

    fun openWorkspace(file: File) {
        repaintNow()
        withWaitCursor {
            closeAllFrames()
            val decoder = XMLDecoder(FileInputStream(file))
            config = decoder.readObject() as BrowserConfig
            readMDIConfig(config.mdiConfig)
            decoder.close()
            recentMenu.addRecentWorkspace(file)
        }
        workspaceFile = file
        updateTitle()
        actionSaveWorkspaceAs.isEnabled = true
    }

    private fun openClassFromFile(file: File): BrowserInternalFrame {
        val frame = BrowserInternalFrame(desktopManager, WindowState(file.path))
        val classFile = frame.classFile
        if (classFile != null) {
            try {
                val pathComponents = classFile.thisClassName.split("/".toRegex())
                val currentDirectory = findClassPathRootDirectory(file, pathComponents)
                if (currentDirectory != null) {
                    config.addClasspathDirectory(currentDirectory.path)
                }
            } catch (e: InvalidByteCodeException) {
            }
        }
        return frame
    }

    private fun findClassPathRootDirectory(file: File, pathComponents: List<String>): File? {
        var currentDirectory = file.parentFile
        for (i in pathComponents.size - 2 downTo 0) {
            if (currentDirectory.name != pathComponents[i]) {
                return null
            }
            currentDirectory = currentDirectory.parentFile
        }
        return currentDirectory
    }

    fun openExternalFile(path: String) {
        EventQueue.invokeLater {
            val file = File(path)
            if (file.exists()) {
                if (path.toLowerCase().endsWith("." + WORKSPACE_FILE_SUFFIX)) {
                    openWorkspace(file)
                } else if (path.toLowerCase().endsWith(".class")) {
                    try {
                        openClassFromFile(file).apply {
                            try {
                                setMaximum(true)
                            } catch (e: PropertyVetoException) {
                            }
                        }
                    } catch (e: IOException) {
                        GUIHelper.showMessage(this@BrowserMDIFrame, e.message, JOptionPane.ERROR_MESSAGE)
                    }
                }
            }
        }
    }


    private fun setupMenu() {
        jMenuBar = JMenuBar().apply {

            add(JMenu("File").apply {
                add(actionOpenClassFile)
                addSeparator()
                add(actionNewWorkspace)
                add(actionOpenWorkspace)
                add(recentMenu)
                addSeparator()
                add(actionSaveWorkspace)
                add(actionSaveWorkspaceAs)
                addSeparator()
                add(actionShowHomepage)
                add(actionShowEJT)
                addSeparator()
                add(actionQuit)
            })

            add(JMenu("Classpath").apply {
                add(actionBrowseClasspath)
                add(actionSetupClasspath)
            })

            add(JMenu("Browse").apply {
                add(actionBackward).apply {
                    accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_MASK)
                }
                add(actionForward).apply {
                    accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_MASK)
                }

                addSeparator()
                add(actionReload).apply {
                    accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_MASK)
                }
            })

            add(menuWindow)

            add(JMenu("Help").apply {
                add(actionShowHelp).apply {
                    accelerator = KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0)
                }
                add(actionAbout)
            })
        }

    }

    private fun setupFrame() {
        defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE

        val contentPane = contentPane as JComponent
        contentPane.layout = BorderLayout(5, 5)
        contentPane.add(JScrollPane(desktopPane).apply {
            GUIHelper.setDefaultScrollbarUnits(this)
        }, BorderLayout.CENTER)

        contentPane.add(buildToolbar(), BorderLayout.NORTH)
        iconImages = listOf(ICON_APPLICATION_16, ICON_APPLICATION_32).map { it.image }

        contentPane.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferHandler.TransferSupport): Boolean {
                val supported = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                if (supported) {
                    support.dropAction = TransferHandler.COPY
                }
                return supported
            }

            override fun importData(support: TransferHandler.TransferSupport): Boolean {
                val transferable = support.transferable
                val flavors = transferable.transferDataFlavors
                for (flavor in flavors) {
                    try {
                        if (flavor.isFlavorJavaFileListType) {
                            @Suppress("UNCHECKED_CAST")
                            (transferable.getTransferData(flavor) as List<File>).forEach {
                                openExternalFile(it.path)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                }
                return false
            }
        }

    }

    private fun updateTitle() {
        val workspaceFile = this.workspaceFile
        if (workspaceFile == null) {
            title = APPLICATION_TITLE
            actionSaveWorkspaceAs.isEnabled = false
        } else {
            title = APPLICATION_TITLE + " [" + workspaceFile.name + "]"
        }
    }

    private fun buildToolbar(): JToolBar = JToolBar().apply {
        add(actionOpenClassFile)
        add(actionBrowseClasspath)
        addSeparator()
        add(actionOpenWorkspace)
        add(actionSaveWorkspace)
        addSeparator()
        add(actionBackward)
        add(actionForward)
        addSeparator()
        add(actionReload)
        addSeparator()
        add(actionShowHomepage)

        isFloatable = false
    }

    private fun repaintNow() {
        val contentPane = contentPane as JComponent
        contentPane.paintImmediately(0, 0, contentPane.width, contentPane.height)
        val menuBar = jMenuBar
        menuBar.paintImmediately(0, 0, menuBar.width, menuBar.height)
    }

    private fun loadSettings() {
        Preferences.userNodeForPackage(javaClass).apply {
            workspaceChooserPath = get(SETTINGS_WORKSPACE_CHOOSER_PATH, workspaceChooserPath)
            classesChooserPath = get(SETTINGS_CLASSES_CHOOSER_PATH, classesChooserPath)
            recentMenu.read(this)
        }
    }

    private fun saveSettings() {
        Preferences.userNodeForPackage(javaClass).apply {
            put(SETTINGS_WORKSPACE_CHOOSER_PATH, workspaceChooserPath)
            put(SETTINGS_CLASSES_CHOOSER_PATH, classesChooserPath)
            recentMenu.save(this)
        }
    }

    private fun saveWorkspace() {
        val fileChooser = workspaceFileChooser
        val result = fileChooser.showSaveDialog(this)
        if (result == JFileChooser.APPROVE_OPTION) {
            val selectedFile = getWorkspaceFile(fileChooser.selectedFile)
            if (selectedFile.exists() && GUIHelper.showOptionDialog(this,
                    "The file " + selectedFile.path + "\nexists. Do you want to overwrite this file?",
                    GUIHelper.YES_NO_OPTIONS,
                    JOptionPane.QUESTION_MESSAGE) == 0)
            {
                saveWorkspaceToFile(selectedFile)
                workspaceFile = selectedFile
                updateTitle()
                workspaceChooserPath = fileChooser.currentDirectory.absolutePath
            }
        }
    }

    private fun getWorkspaceFile(selectedFile: File): File {
        if (!selectedFile.name.toLowerCase().endsWith("." + WORKSPACE_FILE_SUFFIX)) {
            return File(selectedFile.path + "." + WORKSPACE_FILE_SUFFIX)
        } else {
            return selectedFile
        }
    }

    private fun saveWorkspaceToFile(file: File) {
        config.mdiConfig = createMDIConfig()
        try {
            val fos = FileOutputStream(file)
            val encoder = XMLEncoder(fos)
            encoder.writeObject(config)
            encoder.close()
            recentMenu.addRecentWorkspace(file)
        } catch (e: FileNotFoundException) {
            GUIHelper.showMessage(this, "An error occurred while saving to " + file.path, JOptionPane.ERROR_MESSAGE)
        }

        GUIHelper.showMessage(this, "Workspace saved to " + file.path, JOptionPane.INFORMATION_MESSAGE)
        actionSaveWorkspaceAs.isEnabled = true
    }

    @Throws(IOException::class)
    private fun openClassFromJar(file: File): BrowserInternalFrame? {
        val entry = ClasspathArchiveEntry().apply { fileName = file.path }
        jarBrowser.apply {
            clear()
            setClasspathComponent(entry)
            isVisible = true
        }
        val selectedClassName = jarBrowser.selectedClassName ?: return null

        val fileName = file.path + "!" + selectedClassName + ".class"

        val frame = BrowserInternalFrame(desktopManager, WindowState(fileName))
        val classFile = frame.classFile
        if (classFile != null) {
            config.addClasspathArchive(file.path)
        }
        return frame
    }

    private fun withWaitCursor(function: () -> Unit) {
        cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
        try {
            function.invoke()
        } catch (e: FileNotFoundException) {
            GUIHelper.showMessage(this, "File not found: " + e.message, JOptionPane.ERROR_MESSAGE)
        } catch (e: IOException) {
            GUIHelper.showMessage(this, e.message, JOptionPane.ERROR_MESSAGE)
        } finally {
            cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
        }
    }

    fun setWindowActionsEnabled(enabled: Boolean) {
        nextWindowAction.isEnabled = enabled
        previousWindowAction.isEnabled = enabled
        tileWindowsAction.isEnabled = enabled
        stackWindowsAction.isEnabled = enabled
    }

    private fun closeAllFrames() {
        val frames = desktopManager.openFrames
        while (frames.size > 0) {
            frames[0].doDefaultCloseAction()
        }
    }

    private fun createMDIConfig() = MDIConfig().apply {
        internalFrameDescs = desktopManager.openFrames.map { openFrame ->
            val bounds = openFrame.normalBounds
            val internalFrameDesc = MDIConfig.InternalFrameDesc().apply {
                initParam = openFrame.initParam
                x = bounds.x
                y = bounds.y
                width = bounds.width
                height = bounds.height
                isMaximized = openFrame.isMaximum
                isIconified = openFrame.isIcon
            }

            if (openFrame === desktopPane.selectedFrame) {
                activeFrameDesc = internalFrameDesc
            }
            internalFrameDesc
        }
    }

    private fun readMDIConfig(config: MDIConfig) {

        var anyFrameMaximized = false
        config.internalFrameDescs.forEach { internalFrameDesc ->
            val frame: BrowserInternalFrame
            try {
                frame = BrowserInternalFrame(desktopManager, internalFrameDesc.initParam)
                desktopManager.resizeFrame(frame, internalFrameDesc.x, internalFrameDesc.y, internalFrameDesc.width, internalFrameDesc.height)

                val frameMaximized = internalFrameDesc.isMaximized
                anyFrameMaximized = anyFrameMaximized || frameMaximized

                try {
                    if (frameMaximized || anyFrameMaximized) {
                        frame.setMaximum(true)
                    } else if (internalFrameDesc.isIconified) {
                        frame.setIcon(true)
                    }
                } catch (ex: PropertyVetoException) {
                }

                if (internalFrameDesc === config.activeFrameDesc) {
                    desktopManager.setActiveFrame(frame)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        desktopManager.showAll()
    }

    private fun setupEventHandlers() {

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent?) {
                actionQuit()
            }
        })

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent?) {
                desktopManager.checkResizeInMaximizedState()
                recordLastNormalFrameBounds()
            }

            override fun componentMoved(event: ComponentEvent?) {
                recordLastNormalFrameBounds()
            }
        })

    }

    private fun saveWindowSettings() {

        val preferences = Preferences.userNodeForPackage(javaClass)

        val maximized = extendedState and Frame.MAXIMIZED_BOTH != 0
        preferences.putBoolean(SETTINGS_WINDOW_MAXIMIZED, maximized)

        val frameBounds = if (maximized) lastNormalFrameBounds else bounds
        if (frameBounds != null) {
            preferences.apply {
                putInt(SETTINGS_WINDOW_WIDTH, frameBounds.width)
                putInt(SETTINGS_WINDOW_HEIGHT, frameBounds.height)
                putInt(SETTINGS_WINDOW_X, frameBounds.x)
                putInt(SETTINGS_WINDOW_Y, frameBounds.y)
            }
        }
    }

    private fun loadWindowSettings() {

        val preferences = Preferences.userNodeForPackage(javaClass)
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val screenBounds = Rectangle(screenSize)

        val windowX = preferences.getInt(SETTINGS_WINDOW_X, (screenSize.getWidth() - DEFAULT_WINDOW_WIDTH).toInt() / 2)
        val windowY = preferences.getInt(SETTINGS_WINDOW_Y, (screenSize.getHeight() - DEFAULT_WINDOW_HEIGHT).toInt() / 2)
        val windowWidth = preferences.getInt(SETTINGS_WINDOW_WIDTH, DEFAULT_WINDOW_WIDTH)
        val windowHeight = preferences.getInt(SETTINGS_WINDOW_HEIGHT, DEFAULT_WINDOW_HEIGHT)

        val frameBounds = Rectangle(windowX, windowY, windowWidth, windowHeight)
        // sanitize frame bounds
        frameBounds.translate(-Math.min(0, frameBounds.x), -Math.min(0, frameBounds.y))
        frameBounds.translate(-Math.max(0, frameBounds.x + frameBounds.width - screenSize.width), -Math.max(0, frameBounds.y + frameBounds.height - screenSize.height))

        bounds = screenBounds.intersection(frameBounds)

        if (preferences.getBoolean(SETTINGS_WINDOW_MAXIMIZED, false)) {
            extendedState = Frame.MAXIMIZED_BOTH
        }

    }

    private fun recordLastNormalFrameBounds() {
        if (extendedState and Frame.MAXIMIZED_BOTH == 0) {
            val frameBounds = bounds
            if (frameBounds.x >= 0 && frameBounds.y >= 0) {
                lastNormalFrameBounds = frameBounds
            }
        }
    }

    fun invalidateDesktopPane() {
        desktopPane.apply {
            preferredSize = null
            invalidate()
            parent.validate()
            SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this)?.revalidate()
        }
    }

    class DefaultAction(name: String, shortDescription: String? = null, smallIconFileName: String? = null, largeIconFileName: String? = null, private val action: () -> Unit) : AbstractAction(name) {
        init {
            val smallIcon = if (smallIconFileName != null) getIcon(smallIconFileName) else GUIHelper.ICON_EMPTY
            putValue(Action.SMALL_ICON, smallIcon)
            if (largeIconFileName != null) {
                putValue(Action.LARGE_ICON_KEY, getIcon(largeIconFileName))
            }
            if (shortDescription != null) {
                putValue(Action.SHORT_DESCRIPTION, shortDescription)
            }
        }

        override fun actionPerformed(ev: ActionEvent) = invoke()

        operator fun invoke() {
            action.invoke()
        }
    }

    init {
        loadSettings()
        setupMenu()
        setupEventHandlers()
        loadWindowSettings()
        setupFrame()
        updateTitle()
    }

    companion object {

        private val SETTINGS_WORKSPACE_CHOOSER_PATH = "workspaceChooserPath"
        private val SETTINGS_CLASSES_CHOOSER_PATH = "classesChooserPath"
        private val DEFAULT_WINDOW_WIDTH = 800
        private val DEFAULT_WINDOW_HEIGHT = 600

        private val SETTINGS_WINDOW_WIDTH = "windowWidth"
        private val SETTINGS_WINDOW_HEIGHT = "windowHeight"
        private val SETTINGS_WINDOW_X = "windowX"
        private val SETTINGS_WINDOW_Y = "windowY"
        private val SETTINGS_WINDOW_MAXIMIZED = "windowMaximized"

        private val icons = hashMapOf<String, ImageIcon>()
        @JvmField
        val ICON_APPLICATION_16 = getIcon("jclasslib_16.png")
        @JvmField
        val ICON_APPLICATION_32 = getIcon("jclasslib_32.png")


        fun getIcon(fileName: String): ImageIcon =
                icons.getOrPut(fileName) {
                    ImageIcon(BrowserMDIFrame::class.java.getResource("images/" + fileName))
                }

    }
}
