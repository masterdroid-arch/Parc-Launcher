package com.brightblu.parc.feature.search.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/**
 * Production [ContactSource] over `ContactsContract` (T67). Reads names and phone numbers only;
 * never e-mail, photos, notes or accounts, and nothing is logged.
 *
 * `READ_CONTACTS` is declared in this module's manifest and requested at runtime from the search
 * sheet's "Search contacts" row. Without it [search] returns an empty list without touching the
 * provider. The provider query narrows candidates; [ContactRanker] re-checks every match in memory,
 * so a provider that ignores part of the selection cannot produce a wrong hit.
 */
class AndroidContactsSource(private val context: Context) : ContactSource {

    override fun hasPermission(): Boolean =
        com.brightblu.parc.core.data.DistributionFeatures.contacts(context) &&
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun search(query: String, limit: Int): List<ContactRecord> {
        if (limit <= 0 || query.isBlank() || !hasPermission()) return emptyList()
        val records = try {
            queryProvider(query.trim())
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the query.
            return emptyList()
        } catch (_: IllegalArgumentException) {
            // A vendor provider rejected the selection or a column; rank without contacts.
            return emptyList()
        } catch (_: android.database.sqlite.SQLiteException) {
            return emptyList()
        }
        return records.take(limit)
    }

    private fun queryProvider(query: String): List<ContactRecord> {
        val digits = query.filter(Char::isDigit)
        val like = "%$query%"
        val selection: String
        val arguments: Array<String>
        if (digits.length >= 3) {
            selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?" +
                " OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?" +
                " OR ${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} LIKE ?"
            arguments = arrayOf(like, like, "%$digits%")
        } else {
            selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?" +
                " OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            arguments = arrayOf(like, like)
        }

        val byId = LinkedHashMap<Long, MutableContact>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            Projection,
            selection,
            arguments,
            null,
        ) ?: return emptyList()
        cursor.use {
            var rows = 0
            while (it.moveToNext() && rows < MaxRows) {
                rows++
                val id = it.getLong(IdColumn)
                if (id <= 0L) continue
                val name = it.getString(NameColumn)?.trim().orEmpty()
                val number = it.getString(NumberColumn)?.trim().orEmpty()
                val record = byId.getOrPut(id) { MutableContact(id) }
                if (record.name.isEmpty() && name.isNotEmpty()) record.name = name
                if (number.isNotEmpty() && number !in record.numbers) record.numbers.add(number)
            }
        }
        return byId.values.map { ContactRecord(it.id, it.name, it.numbers.toList()) }
    }

    private class MutableContact(val id: Long) {
        var name: String = ""
        val numbers: MutableList<String> = mutableListOf()
    }

    private companion object {
        /** Enough rows for a personal address book; the ranker then keeps only a few. */
        const val MaxRows = 1_000

        val Projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )

        const val IdColumn = 0
        const val NameColumn = 1
        const val NumberColumn = 2
    }
}
