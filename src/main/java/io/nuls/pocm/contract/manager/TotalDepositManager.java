/**
 * MIT License
 * <p>
 * Copyright (c) 2017-2018 nuls.io
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.nuls.pocm.contract.manager;

import java.math.BigInteger;

import static io.nuls.pocm.contract.util.PocmUtil.toNuls;

/**
 * @author: PierreLuo
 * @date: 2019-05-14
 */
public class TotalDepositManager {
    // 总抵押金额
    private BigInteger totalDeposit;

    private ConsensusManager consensusManager;

    private boolean openConsensus;

    public TotalDepositManager(ConsensusManager consensusManager, boolean openConsensus) {
        this.totalDeposit = BigInteger.ZERO;
        this.consensusManager = consensusManager;
        this.openConsensus = openConsensus;
    }

    public BigInteger getTotalDeposit() {
        BigInteger total = totalDeposit;
        return total;
    }

    public String getTotalDepositDetail() {
        String result = "running: " + toNuls(totalDeposit).toPlainString() + " NULS";
        BigInteger total = totalDeposit;
        if(openConsensus) {
            BigInteger totalTakeBackLockDeposit = consensusManager.getTotalTakeBackLockDeposit();
            if(totalTakeBackLockDeposit.compareTo(BigInteger.ZERO) > 0) {
                result += ", waiting for exit: " + toNuls(totalTakeBackLockDeposit).toPlainString() + " NULS";
            }
        }
        return result;
    }

    public void add(BigInteger value) {
        this.totalDeposit = this.totalDeposit.add(value);
        if(openConsensus && consensusManager.isUnLockedConsensus()) {
            consensusManager.createOrDepositIfPermitted(value, consensusManager.checkCurrentReset());
        }
    }

    public boolean subtract(BigInteger value) {
        this.totalDeposit = this.totalDeposit.subtract(value);
        if(openConsensus && consensusManager.isUnLockedConsensus()) {
            consensusManager.checkCurrentReset();
            return consensusManager.withdrawIfPermitted(value);
        } else {
            return true;
        }
    }
}
