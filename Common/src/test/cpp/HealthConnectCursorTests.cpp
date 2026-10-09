#include "healthconnectcursor.hpp"
#include <cassert>
#include <thread>

int main() {
  uint16_t cursor = 100;
  auto token = healthconnect::snapshot(&cursor, 10, 1000);
  assert(healthconnect::advance(&cursor, token, 600));
  assert(cursor == 600);
  // Latest PR finding: a rewind before the successful insert acknowledgement.
  token = healthconnect::snapshot(&cursor, 10, 1000);
  std::thread writer([&] { healthconnect::gapFilled(&cursor, 50); });
  writer.join();
  assert(!healthconnect::advance(&cursor, token, 1000));
  assert(cursor == 50);
  // A gap INSIDE the chunk need not rewind its start; still invalidate it.
  token = healthconnect::snapshot(&cursor, 10, 1000);
  healthconnect::gapFilled(&cursor, 200);
  assert(cursor == 50);
  assert(!healthconnect::advance(&cursor, token, 550));
  token = healthconnect::snapshot(&cursor, 10, 1000);
  assert(healthconnect::advance(&cursor, token, 550));
  assert(!healthconnect::advance(&cursor, token, 600));
  // A reset invalidates a same-position snapshot as well (the ABA case).
  token = healthconnect::snapshot(&cursor, 10, 1000);
  healthconnect::reset(&cursor, 550);
  assert(!healthconnect::advance(&cursor, token, 1000));
  token = healthconnect::snapshot(&cursor, 10, 1000);
  assert(!healthconnect::advance(&cursor, token, 1001));
  assert(healthconnect::advance(&cursor, token, 1000));
  healthconnect::reset(&cursor, 0);
  token = healthconnect::snapshot(&cursor, 10, 1000);
  assert(uint16_t(token) == 10);
  assert(healthconnect::advance(&cursor, token, 510));
  // A manual QR correction overwrites a nonempty glucose slot behind the cursor.
  token = healthconnect::snapshot(&cursor, 10, 1000);
  healthconnect::glucoseWritten(&cursor, 100, 1000, 1200);
  assert(cursor == 100);
  assert(!healthconnect::advance(&cursor, token, 1000));
  // A correction inside an in-flight snapshot must invalidate its acknowledgement.
  token = healthconnect::snapshot(&cursor, 10, 1000);
  healthconnect::glucoseWritten(&cursor, 300, 1100, 1250);
  assert(cursor == 100);
  assert(!healthconnect::advance(&cursor, token, 600));
  // Same-value replay and invalid outputs cause no extra revision/rewind.
  token = healthconnect::snapshot(&cursor, 10, 1000);
  healthconnect::glucoseWritten(&cursor, 50, 1200, 1200);
  healthconnect::glucoseWritten(&cursor, 50, 1200, 0);
  assert(healthconnect::advance(&cursor, token, 600));
  // Missing slots still take the same path.
  token = healthconnect::snapshot(&cursor, 10, 1000);
  healthconnect::glucoseWritten(&cursor, 150, 0, 1300);
  assert(cursor == 150);
  assert(!healthconnect::advance(&cursor, token, 1000));
}
