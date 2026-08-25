#!/usr/bin/env sh
# Dumps the database on an interval and keeps the last N dumps. Runs in its own
# container so a backup never depends on the API being healthy.
set -eu

dir=${NFCIT_BACKUP_DIR:-/backups}
interval=${NFCIT_BACKUP_INTERVAL_SECONDS:-43200}
keep=${NFCIT_BACKUP_KEEP:-14}

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: $*"; }

mkdir -p "$dir"
log "every ${interval}s, keeping ${keep} dumps in ${dir}"

while true; do
	name="$dir/nfcit-$(date -u +%Y%m%dT%H%M%SZ).dump"

	# Written aside and moved into place, so a dump interrupted halfway never
	# takes a rotation slot from a complete one.
	if pg_dump --format=custom --file="$name.part"; then
		mv "$name.part" "$name"
		log "wrote $(basename "$name") ($(du -h "$name" | cut -f1))"

		ls -1t "$dir"/nfcit-*.dump 2>/dev/null | tail -n "+$((keep + 1))" | while read -r old; do
			rm -f "$old"
			log "rotated out $(basename "$old")"
		done
	else
		# Keep the schedule: a database that is down now may well be up in
		# twelve hours, and exiting would only lose the next dumps too.
		rm -f "$name.part"
		log "dump failed, retrying at the next tick"
	fi

	sleep "$interval"
done
