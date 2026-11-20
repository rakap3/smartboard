package id.kelasku.core

/**
 * How Edu Launcher talks to Papan Guru. Both sides are guarded by [PERMISSION], a signature
 * permission declared by both apps, so only apps signed with the same key can use them.
 */
object BoardsContract {
    const val PERMISSION = "id.kelasku.permission.BOARDS"

    /** Exported, permission-protected alias of the board activity; the only entry that honours the extras below. */
    const val ENTRY_ACTIVITY = "id.kelasku.papanguru.LauncherEntry"

    /** Open this board id. */
    const val EXTRA_BOARD = "id.kelasku.papanguru.BOARD"
    /** Create a new board (named [EXTRA_NAME] when given). */
    const val EXTRA_NEW = "id.kelasku.papanguru.NEW"
    const val EXTRA_NAME = "id.kelasku.papanguru.NAME"
    /** Import the PDF in the intent data (with a read grant) into the new board. */
    const val EXTRA_PDF = "id.kelasku.papanguru.PDF"
    /** Show the board list. */
    const val EXTRA_BOARDS = "id.kelasku.papanguru.BOARDS"

    /** Provider authority for a Papan Guru package (dev builds use the `.dev` package). */
    fun authority(boardPackage: String) = "$boardPackage.boards"

    /** `content://<authority>/boards`: one row per board, most recently modified first. */
    const val PATH_BOARDS = "boards"
    /** `content://<authority>/thumb/<id>`: the board's PNG thumbnail (openFile). */
    const val PATH_THUMB = "thumb"

    const val COLUMN_ID = "id"
    const val COLUMN_NAME = "name"
    const val COLUMN_MODIFIED = "modified"
    const val COLUMN_PAGES = "pages"
}
