package io.github.avikulin.thud.ui.editor

/**
 * Generic undo/redo manager that maintains state history.
 *
 * @param T The type of state to track
 * @param maxHistory Maximum number of states to keep in history (default 50)
 */
class UndoRedoManager<T>(private val maxHistory: Int = 50) {

    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    /**
     * Save a new state to history.
     * This clears the redo stack since a new action branches the history.
     */
    fun saveState(state: T) {
        undoStack.addLast(state)

        // Trim history if exceeds max
        while (undoStack.size > maxHistory) {
            undoStack.removeFirst()
        }

        // Clear redo stack when new action is performed
        redoStack.clear()
    }

    /**
     * Undo the last action.
     * @param currentState The current state before undoing (will be added to redo stack)
     * @return The previous state, or null if nothing to undo
     */
    fun undo(currentState: T): T? {
        if (undoStack.isEmpty()) return null

        // Push current state to redo stack
        redoStack.addLast(currentState)

        // Pop and return previous state
        return undoStack.removeLast()
    }

    /**
     * Redo a previously undone action.
     * @param currentState The current state before redoing (will be added to undo stack)
     * @return The redone state, or null if nothing to redo
     */
    fun redo(currentState: T): T? {
        if (redoStack.isEmpty()) return null

        // Push current state to undo stack
        undoStack.addLast(currentState)

        // Pop and return next state
        return redoStack.removeLast()
    }

    /**
     * Check if undo is available.
     */
    fun canUndo(): Boolean = undoStack.isNotEmpty()

    /**
     * Check if redo is available.
     */
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /**
     * Clear all history (both undo and redo stacks).
     * Call this when switching to a different context (e.g., different workout).
     */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    /**
     * Get the number of states in undo stack.
     */
    val undoCount: Int get() = undoStack.size

    /**
     * Get the number of states in redo stack.
     */
    val redoCount: Int get() = redoStack.size
}
