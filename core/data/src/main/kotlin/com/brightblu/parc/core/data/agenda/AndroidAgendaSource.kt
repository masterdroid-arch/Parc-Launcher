package com.brightblu.parc.core.data.agenda

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract

/**
 * The platform port for the Agenda page (T100): reads the Calendar provider through the public
 * [CalendarContract] contract only (no account access, no sync, nothing written). Every call is
 * guarded: a missing permission, a missing provider, no calendars or a malformed row yields an
 * empty or [AgendaRead.NoPermission] result and never an exception. Call off the main thread.
 */
class AndroidAgendaSource(context: Context) : AgendaSource {
    private val appContext = context.applicationContext

    override fun calendars(): List<AgendaCalendar>? {
        if (!granted(appContext)) return null
        return try {
            val out = ArrayList<AgendaCalendar>()
            appContext.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.CALENDAR_COLOR),
                null, null, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            )?.use { c ->
                while (c.moveToNext() && out.size < MaxCalendars) {
                    try {
                        val name = c.getString(1)?.trim().orEmpty()
                        if (name.isEmpty()) continue
                        out += AgendaCalendar(c.getLong(0), name, if (c.isNull(2)) 0 else c.getInt(2))
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
            out.distinctBy { it.id }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun events(fromMs: Long, toMs: Long): AgendaRead {
        if (!granted(appContext)) return AgendaRead.NoPermission
        return try {
            val builder: Uri.Builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, fromMs - AgendaRules.QueryPaddingMs)
            ContentUris.appendId(builder, toMs + AgendaRules.QueryPaddingMs)
            val out = ArrayList<AgendaEvent>()
            appContext.contentResolver.query(
                builder.build(),
                arrayOf(
                    CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_COLOR,
                ),
                null, null, CalendarContract.Instances.BEGIN + " ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < AgendaRules.MaxEvents * 4) {
                    try {
                        if (c.isNull(3)) continue
                        val begin = c.getLong(3)
                        val end = if (c.isNull(4)) begin else c.getLong(4)
                        out += AgendaEvent(
                            eventId = c.getLong(0), calendarId = c.getLong(1), title = c.getString(2)?.trim().orEmpty(),
                            beginMs = begin, endMs = end, allDay = !c.isNull(5) && c.getInt(5) == 1,
                            location = c.getString(6)?.trim()?.takeIf { it.isNotEmpty() },
                            color = if (c.isNull(7)) 0 else c.getInt(7),
                        )
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
            AgendaRead.Data(out)
        } catch (e: SecurityException) {
            AgendaRead.NoPermission
        } catch (e: Exception) {
            AgendaRead.Data(emptyList())
        }
    }

    companion object {
        private const val MaxCalendars = 100

        fun granted(context: Context): Boolean = try {
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }

        /** The view intent target for an event, or null for a bad id. */
        fun eventUri(eventId: Long): Uri? =
            if (eventId > 0) ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId) else null
    }
}
