package app.auriel.edenlauncher.util

import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LongSparseArray

/**
 * Two-way map between [UserHandle] and the serial number stored in the database.
 *
 * `UserManager` calls are binder round-trips; the loader touches them once per row, so both
 * directions are memoised. Bounded by the number of profiles on the device (typically one or two).
 */
class UserCache(context: Context) {

    private val userManager = context.getSystemService(UserManager::class.java)
    private val serialToUser = LongSparseArray<UserHandle>(2)
    private val userToSerial = HashMap<UserHandle, Long>(2)

    init {
        val self = Process.myUserHandle()
        val serial = userManager.getSerialNumberForUser(self)
        serialToUser.put(serial, self)
        userToSerial[self] = serial
    }

    fun serialFor(user: UserHandle): Long = synchronized(this) {
        userToSerial.getOrPut(user) {
            userManager.getSerialNumberForUser(user).also { serialToUser.put(it, user) }
        }
    }

    /** Null when the profile is gone - the caller drops the row rather than guessing. */
    fun userFor(serial: Long): UserHandle? = synchronized(this) {
        serialToUser.get(serial) ?: userManager.getUserForSerialNumber(serial)?.also {
            serialToUser.put(serial, it)
            userToSerial[it] = serial
        }
    }
}
