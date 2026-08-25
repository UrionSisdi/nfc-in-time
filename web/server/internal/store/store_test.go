package store

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"

	"github.com/urionsisdi/nfc-in-time/web/server/internal/keys"
)

// open connects to the database named by NFCIT_TEST_DB and starts every test
// from an empty schema. Without that variable the package still builds and the
// unit suite runs; these tests need a real Postgres because what they check is
// the locking and the arithmetic, not the Go around it.
func open(t *testing.T) (*Store, context.Context) {
	t.Helper()
	dsn := os.Getenv("NFCIT_TEST_DB")
	if dsn == "" {
		t.Skip("set NFCIT_TEST_DB to run store tests")
	}

	ctx := context.Background()
	s, err := Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Close)

	if _, err := s.pool.Exec(ctx, `DROP SCHEMA public CASCADE; CREATE SCHEMA public`); err != nil {
		t.Fatal(err)
	}
	if err := s.Migrate(ctx); err != nil {
		t.Fatal(err)
	}
	return s, ctx
}

const genesis = 788_940_000

func register(t *testing.T, s *Store, ctx context.Context, tgID string, now int64) Player {
	t.Helper()
	p, _, err := s.Register(ctx, tgID, "player "+tgID, []byte("key-"+tgID), keys.ID("id-"+tgID), genesis, now)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func TestGenesisIsGrantedOncePerAccount(t *testing.T) {
	s, ctx := open(t)
	now := time.Now().Unix()

	first := register(t, s, ctx, "1", now)
	if got := first.BalanceAt(now); got != genesis {
		t.Fatalf("new player got %d seconds, want %d", got, genesis)
	}

	// Spend some, then sign in again from a second installation.
	register(t, s, ctx, "2", now)
	transfer(t, s, ctx, "1", "2", 1000, "n1", now)

	again, created, err := s.Register(ctx, "1", "player 1", []byte("key-1b"), keys.ID("id-1b"), genesis, now)
	if err != nil {
		t.Fatal(err)
	}
	if created {
		t.Fatal("a returning account was treated as new")
	}
	if got := again.BalanceAt(now); got != genesis-1000 {
		t.Fatalf("returning player got %d seconds, want %d", got, genesis-1000)
	}

	// The earlier installation must lose the right to sync.
	if _, err := s.Device(ctx, keys.ID("id-1")); !errors.Is(err, ErrRevoked) {
		t.Fatalf("previous device key is %v, want revoked", err)
	}
	if _, err := s.Device(ctx, keys.ID("id-1b")); err != nil {
		t.Fatalf("current device key is unusable: %v", err)
	}
	// Both keys stay verifiable, so a contact signed before the reinstall holds.
	if all, err := s.PlayerKeys(ctx, "1"); err != nil || len(all) != 2 {
		t.Fatalf("player keys = %d, %v; want 2", len(all), err)
	}
}

func transfer(t *testing.T, s *Store, ctx context.Context, from, to string, amount int64, nonce string, now int64) TransferResult {
	t.Helper()
	res, err := s.ApplyTransfers(ctx, []Transfer{{
		Nonce: nonce, FromTgID: from, ToTgID: to, Amount: amount, SignedAt: now,
	}}, from, now)
	if err != nil {
		t.Fatal(err)
	}
	return res[0]
}

func TestTransferMovesTimeAndDedupes(t *testing.T) {
	s, ctx := open(t)
	now := time.Now().Unix()
	register(t, s, ctx, "1", now)
	register(t, s, ctx, "2", now)

	if res := transfer(t, s, ctx, "1", "2", 3600, "n1", now); res.Applied != 3600 {
		t.Fatalf("applied %d, want 3600", res.Applied)
	}
	res := transfer(t, s, ctx, "1", "2", 3600, "n1", now)
	if !res.Duplicate || res.Applied != 0 {
		t.Fatalf("replay applied %d (duplicate=%v), want 0 and duplicate", res.Applied, res.Duplicate)
	}

	donor, _ := s.Player(ctx, "1")
	recipient, _ := s.Player(ctx, "2")
	if donor.BalanceAt(now) != genesis-3600 || recipient.BalanceAt(now) != genesis+3600 {
		t.Fatalf("balances are %d and %d, want %d and %d",
			donor.BalanceAt(now), recipient.BalanceAt(now), genesis-3600, genesis+3600)
	}
}

func TestDonorPaysOnlyWhatIsLeftAndDies(t *testing.T) {
	s, ctx := open(t)
	now := time.Now().Unix()
	register(t, s, ctx, "1", now)
	register(t, s, ctx, "2", now)

	res := transfer(t, s, ctx, "1", "2", genesis*2, "n1", now)
	if res.Applied != genesis {
		t.Fatalf("applied %d, want the donor's whole balance %d", res.Applied, genesis)
	}

	donor, _ := s.Player(ctx, "1")
	if donor.Alive(now) || donor.DiedAt == nil {
		t.Fatalf("donor is alive=%v died_at=%v, want dead and stamped", donor.Alive(now), donor.DiedAt)
	}
	if got := donor.BalanceAt(now); got != 0 {
		t.Fatalf("dead donor holds %d seconds, want 0", got)
	}

	// A dead player has nothing left to give.
	if res := transfer(t, s, ctx, "1", "2", 60, "n2", now); res.Applied != 0 {
		t.Fatalf("a dead donor paid %d", res.Applied)
	}
	// And time sent to them is burnt rather than banked.
	before, _ := s.Player(ctx, "2")
	transfer(t, s, ctx, "2", "1", 60, "n3", now)
	after, _ := s.Player(ctx, "2")
	revived, _ := s.Player(ctx, "1")
	if revived.Alive(now) {
		t.Fatal("a dead player came back to life on a transfer")
	}
	if after.BalanceAt(now) != before.BalanceAt(now)-60 {
		t.Fatalf("donor kept %d, want %d", after.BalanceAt(now), before.BalanceAt(now)-60)
	}
}

func TestReapDeadStampsPlayersNobodyTouched(t *testing.T) {
	s, ctx := open(t)
	now := time.Now().Unix()
	register(t, s, ctx, "1", now-genesis-10)

	p, _ := s.Player(ctx, "1")
	if p.Alive(now) {
		t.Fatal("a player whose clock ran out still counts as alive")
	}
	if n, err := s.ReapDead(ctx, now); err != nil || n != 1 {
		t.Fatalf("reaped %d, %v; want 1", n, err)
	}
	p, _ = s.Player(ctx, "1")
	if p.DiedAt == nil {
		t.Fatal("reaping left died_at empty")
	}
}

func TestBoardAndTopAggregate(t *testing.T) {
	s, ctx := open(t)
	now := time.Now().Unix()
	register(t, s, ctx, "1", now)
	register(t, s, ctx, "2", now)
	register(t, s, ctx, "3", now-genesis-10)
	transfer(t, s, ctx, "2", "1", 3600, "n1", now)

	listed := true
	for _, id := range []string{"1", "2"} {
		if err := s.UpdateProfile(ctx, id, "", &listed, now); err != nil {
			t.Fatal(err)
		}
	}

	b, err := s.Board(ctx, now)
	if err != nil {
		t.Fatal(err)
	}
	if b.Alive != 2 || b.Dead != 1 {
		t.Fatalf("alive=%d dead=%d, want 2 and 1", b.Alive, b.Dead)
	}
	if b.Deals != 1 || b.Transferred24h != 3600 {
		t.Fatalf("deals=%d transferred=%d, want 1 and 3600", b.Deals, b.Transferred24h)
	}
	// Transfers move time inside the pool without changing its size.
	if b.WorldSeconds != 2*genesis {
		t.Fatalf("world pool is %d, want %d", b.WorldSeconds, 2*genesis)
	}

	top, err := s.Top(ctx, now, 20)
	if err != nil {
		t.Fatal(err)
	}
	if len(top) != 2 {
		t.Fatalf("top lists %d players, want the 2 who opted in", len(top))
	}
	if top[0].Diff24h != 3600 || top[1].Diff24h != -3600 {
		t.Fatalf("24h flow is %d and %d, want +3600 and -3600", top[0].Diff24h, top[1].Diff24h)
	}
}
