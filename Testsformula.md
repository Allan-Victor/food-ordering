# The formula behind every test I wrote

Every single test in that suite was produced by asking four questions in order. Once you know the questions, you can derive any test yourself.

## Question 1: What is the unit under test and what is its job?

Before writing a single line, name the thing being tested and state its responsibility in one sentence.

Examples from our suite:

- **Money** → "normalises monetary amounts to their currency's scale and rejects invalid input"
- **Order.create** → "builds a valid PENDING aggregate from confirmed items and derives the total"
- **CreateOrderService.createOrder** → "orchestrates: resolve restaurant, invoke domain, persist, publish"
- **OrderPersistenceAdapter** → "translates between domain aggregates and database rows faithfully"
- **OrderController** → "translates HTTP requests into port calls and port results into HTTP responses"

That sentence is the test class's scope. If a test you're about to write doesn't fit the sentence, it belongs in a different class.

## Question 2: What are the behaviours worth pinning?

A behaviour is something the unit does, not something it is. For each unit I enumerated behaviours in three categories:

**Happy path** — what happens when everything is valid and present.

**Guard / rejection** — what happens when input violates a rule. One test per distinct guard, named for the rule, not for the input.

**Edge / boundary** — the non-obvious cases that a refactor could silently break. These are the most valuable tests in the suite. Examples:

- `reconstitute` trusts the stored total even when it disagrees with the lines
- A UGX amount round-trips through a scale-4 column and comes back at scale 0
- Failure messages survive a delimiter inside the message text
- `@ElementCollection` rows come back in position order regardless of database row order
- `save` doesn't precede itself with a SELECT (the `@Version` wrapper test)

The edge cases come from reading the design decisions and asking "what would silently break if someone changed this?" Every non-obvious decision in the Javadoc became at least one test.

## Question 3: What should I mock and what should I not mock?

This is Khorikov's core question and the one most tests get wrong. The rule I applied:

**Mock shared dependencies with I/O** — things that talk to a database, a message broker, a network. In our suite: `SaveOrderPort`, `LoadOrderPort`, `LoadRestaurantPort`, `PublishEventPort`. These are mocked in `CreateOrderServiceTest` because their real implementations touch a database and we don't want that in a unit test.

**Never mock domain objects** — `Order`, `Restaurant`, `Money`, `OrderDomainService`. These are pure functions of their arguments with no I/O. Mocking them would mean the test passes while the real rules are broken, which is the definition of a test with negative value. `CreateOrderServiceTest` uses a real `OrderDomainServiceImpl` for exactly this reason.

**Never mock the thing under test** — obvious but worth stating.

A quick test of whether something should be mocked: "if this throws an exception, is it because the unit under test did something wrong, or because infrastructure failed?" Infrastructure failure → mock. Domain failure → use the real thing.

## Question 4: What is the shape of each test?

Every test follows Arrange-Act-Assert, three blocks, no mixing.

```java
// Arrange — build the world the test needs
Order order = pendingOrder();

// Act — do the one thing
order.pay();

// Assert — check the one outcome
assertThat(order.orderStatus()).isEqualTo(OrderStatus.PAID);
```

Three rules I applied to keep tests readable:

1. **One logical assertion per test.** Not one `assertThat` line — one concept. The `roundTripsFaithfully` test has several `assertThat` lines but they all assert the same concept: "nothing was lost or altered." That's one logical assertion. A test that checks status AND price AND failure messages is checking three things; split it.
2. **Name the test for the behaviour, not the method.** `pay_whenPending_setsStatusToPaid` is worse than `pendingToPaid` and far worse than the `@DisplayName("PENDING → PAID")` approach. The name should read as a sentence that describes what the system does.
3. **The test should fail for exactly one reason.** If a test can fail because the status is wrong or because the price is wrong, you won't know which broke without reading the failure output. Each test pins one thing.

## How I derived the test list for each class concretely

Let me show the derivation for `Order` since it's the most complex.

### Step 1: Read Order.java top to bottom. For every guard, write a test name.

- `create` — null customerId → `OrderDomainException`
- `create` — null restaurantId → `OrderDomainException`
- `create` — null deliveryAddress → `OrderDomainException`
- `create` — null items → `OrderDomainException`
- `create` — empty items → `OrderDomainException`

### Step 2: For every computation, write a test that pins the result.

- `create` — total equals sum of lines (2×8000 + 3×2000 = 22000)
- `create` — positions are 1, 2, 3... in order
- `create` — status is PENDING
- `create` — orderId and trackingId are distinct non-null UUIDs
- `create` — item names and prices come from `ConfirmedItem`

### Step 3: For every state transition method, write the happy path and every illegal transition.

- `pay` — PENDING → PAID ✓
- `pay` — from PAID → throws (duplicate message idempotency)
- `approve` — PAID → APPROVED ✓
- `approve` — from PENDING → throws
- `initCancel` — PAID → CANCELLING ✓
- `initCancel` — from PENDING → throws
- `cancel` — CANCELLING → CANCELLED ✓
- `cancel` — PENDING → CANCELLED ✓ (payment never happened)
- `cancel` — from APPROVED → throws

### Step 4: For every non-obvious design decision in the Javadoc, write a test that would catch a revert.

```java
// "failure messages accumulate, not overwrite"
initCancel("reason1") then cancel("reason2") → messages = ["reason1", "reason2"]

// "null and blank messages are filtered"
cancel(["real", null, "   "]) → messages = ["real"]

// "null list is tolerated"
cancel(null) → no throw, messages = []

// "collections are unmodifiable"
order.items() → isUnmodifiable()
order.failureMessages() → isUnmodifiable()
```

### Step 5: For reconstitute specifically — read the Javadoc and write a test for each "why" paragraph.

```java
// "trusts the stored total even when it disagrees"
reconstitute with price=99999 and items that sum to 10000 → price() == 99999

// "restores states create could never produce"
reconstitute with status=CANCELLED → orderStatus() == CANCELLED

// "restores positions rather than renumbering"
reconstitute with positions [7, 9] → positions are [7, 9], not [1, 2]
```

That derivation produces the complete `OrderTest` class mechanically, without intuition.

## The pattern for each layer

Each layer has a characteristic test shape that follows from its responsibility:

**Domain** (no mocks, no Spring):
> real objects in → assert on state or returned value

**Application service** (mock ports, real domain):
> stub ports → call service → assert on port interactions + return value

**Persistence adapter** (`@DataJpaTest`, real H2):
> save via adapter → flushAndClear → load via adapter → assert round-trip

**Web adapter** (`@WebMvcTest`, mock ports):
> HTTP request via MockMvc → assert HTTP response shape + verify port called with right command

**Architecture** (ArchUnit, no runtime):
> import classes → assert structural rule

Once you know which layer a class belongs to, you know its test shape automatically.

## The one meta-rule underneath all of it

Every test answers the question: "if this test fails, what exactly broke?"

If you can't answer that in one sentence, the test is testing too much. If the answer is "something in the domain," the test belongs in the domain tier. If the answer is "the HTTP status was wrong," it belongs in the web tier. The tier structure isn't bureaucracy — it's how you make failures diagnosable.

Write the failure message before you write the test. If the failure message would be confusing, the test is structured wrong.
