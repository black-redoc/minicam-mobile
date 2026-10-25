import Foundation
import Testing
@testable import MinicamKit

/// A clock the test moves by hand.
private final class ManualClock: @unchecked Sendable {
    var now: TimeInterval = 1_000
}

/// docs/specs/011: live view time limit.
@Suite struct ViewingBudgetTests {
    private let clock = ManualClock()
    private var budget: ViewingBudget

    init() {
        let clock = self.clock
        budget = ViewingBudget(clock: { clock.now })
    }

    @Test mutating func viewingBudget_countsOnlyWhileRunning() {
        #expect(budget.remaining == 30)
        clock.now += 5
        #expect(budget.remaining == 30)
        budget.start()
        clock.now += 4
        #expect(budget.remaining == 26)
        #expect(!budget.expired)
    }

    @Test mutating func viewingBudget_keepsUsedTimeAcrossPauses() {
        budget.start()
        clock.now += 10
        budget.stop()
        clock.now += 60
        #expect(budget.remaining == 20)
        budget.start()
        budget.start() // a second start while running changes nothing
        clock.now += 5
        budget.stop()
        budget.stop()
        #expect(budget.remaining == 15)
    }

    @Test mutating func viewingBudget_expiresAtLimit() {
        budget.start()
        clock.now += 29.5
        #expect(!budget.expired)
        clock.now += 0.5
        #expect(budget.expired)
        clock.now += 10
        budget.stop()
        #expect(budget.remaining == 0)
        #expect(budget.expired)
    }
}
