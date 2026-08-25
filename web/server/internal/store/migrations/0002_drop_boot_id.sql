-- The device reported which boot a sync belonged to and nothing ever read it:
-- the balance is an expiry instant on this side, so the server never takes the
-- device's word for how much time it spent. The client detects its own reboots
-- without help.
ALTER TABLE players DROP COLUMN boot_id;
