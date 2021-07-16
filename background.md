## Principles

- Multiple nodes tailing same log, sharing the same object store.
- Dependency tree to derive immutable chunks (with revisions) from each other.
- Declare data dependencies instead of building them. Especially don't do unexpected I/O.
- Persistent data sits in the object store (or log), transient data in memory or disk.
- Evict handled via new chunk version + GC.
- Non-blocking I/O everywhere it makes sense.
- Compiled queries operating on vectors/batches.
- JMH for microbenchmarks of CPU-bound pieces.
- Always also compare CPU-bound pieces with Java.
- Prefer property based testing, including stateful testing.
- API for end-to-end.
- If it gets hairy, take a step back. Avoid locks or synchronisation for example.
- Use of local in-memory Clojure state is discouraged.
- Take a break to read and review papers, avoid NIH.
- Use Java interfaces and types between subsystem boundaries.
- Focus on data, not code.

Data:
- Arrow columnar format everywhere.
- Nippy Arrow-extension type to bootstrap, preference is to later remove it.
- RoaringBitmap Arrow-extension type.
- Arrow used for off-heap memory management.

Two main extension points:
- Single log feeding transactions (but not tied to this usage).
- Shared object store for persistence.

## Problems with Classic

- All storage on every node
- Infinite retention on the log
- Imposing Kafka but not really benefiting from it
- I/O chatty query engine - depends on sorting + hashing
- Designed around timeslice queries - no straightforward way to go to advanced bitemp functionality
- Local maximum in ingest/query performance
- Overly flexible data model

### End scenarios:
- Core2 as a viable alternative to Crux
  Spectrum:
  - A: Drop-in replacement for Crux
  - B: Essentially Crux but with Arrow
  - C: Crux re-thought
- Core2 canned, knowledge pulled into Crux

### Things to consider:
- User migration?
- Maintaining Crux
- Pulling existing users over vs attracting new users

## Risks to Core2:
- Performance
  - Core2 requires scanning everything, queries too slow.
    Mitigated by TPC-H SF, WatDiv
  - Cold caches - low latency queries require remote data transfer
  - Buffer pool doesn't evict anything
  - Can't get temporal index working remotely, append-only
  - By making advanced temporality possible, we forego low-latency performance for as-of-now queries
- Something becomes infeasible to represent in Arrow
- Duplication of ingest work becomes prohibitively expensive
- Expensive to run in cloud
- Stuck trying to replicate Classic

### Non-technical risks:
- Resourcing - scaling team and remaining productive
- External buy-in and understanding, getting people back on board
- External expectation of drop-in replacement
- Crux rename timing
- Classic vs Core2 time/focus
- Keeping the Classic flame alive while we're working on Core2