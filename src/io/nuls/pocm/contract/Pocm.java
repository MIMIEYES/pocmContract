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
package io.nuls.pocm.contract;

import io.nuls.contract.sdk.Address;
import io.nuls.contract.sdk.Block;
import io.nuls.contract.sdk.Contract;
import io.nuls.contract.sdk.Msg;
import io.nuls.contract.sdk.annotation.Payable;
import io.nuls.contract.sdk.annotation.Required;
import io.nuls.contract.sdk.annotation.View;
import io.nuls.pocm.contract.event.DepositInfoEvent;
import io.nuls.pocm.contract.event.MiningInfoEvent;
import io.nuls.pocm.contract.model.*;
import io.nuls.pocm.contract.token.PocmToken;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static io.nuls.contract.sdk.Utils.emit;
import static io.nuls.contract.sdk.Utils.require;
import static io.nuls.pocm.contract.util.PocmUtil.*;

/**
 * @author: Long
 * @date: 2019-03-15
 */
public class Pocm extends PocmToken implements Contract {

    // 合约创建高度
    private final long createHeight;
    // 初始价格，每个奖励周期所有的NULS抵押数平分XX个token
    private BigDecimal initialPrice;

    // 奖励发放周期（参数类型为数字，每过XXXX块发放一次）
    private int awardingCycle;
    // 奖励减半周期（可选参数，若选择，则参数类型为数字，每XXXXX块奖励减半）
    private int rewardHalvingCycle;
    // 最低抵押na数量(1亿个na等于1个NULS）
    private BigInteger minimumDeposit;
    // 最短锁定区块（参数类型为数字，XXXXX块后才可退出抵押）
    private int minimumLocked;
    // 最大抵押地址数量（可选参数）
    private int maximumDepositAddressCount;

    //接收空投地址列表
    private List<AirdropperInfo> ariDropperInfos = new ArrayList<AirdropperInfo>();

    //用户抵押信息(key为抵押者地址）
    private Map<String, DepositInfo> depositUsers = new HashMap<String, DepositInfo>();

    // 用户挖矿信息(key为接收挖矿Token地址）
    private Map<String, MiningInfo> mingUsers = new HashMap<String, MiningInfo>();

    //历史挖矿信息，暂未使用
    private Map<String, MiningInfo> mingUserHis=  new HashMap<String, MiningInfo>();

    // 总抵押金额
    private BigInteger totalDeposit;
    // 总抵押地址数量
    private int totalDepositAddressCount;

    //每个奖励周期的抵押金额索引，k-v：奖励周期-List序号
    private Map<Integer,Integer> totalDepositIndex = new  LinkedHashMap<Integer,Integer>();
    //抵押金额列表，与索引表联合使用
    private List<RewardCycleInfo> totalDepositList = new LinkedList<RewardCycleInfo>();
    //上一次抵押数量有变动的奖励周期
    private int lastCalcCycle=0;
    //上一次抵押数量有变动时的高度
    private long lastCalcHeight=0L;

    private static long NUMBER=1L;


    public Pocm(@Required String name,@Required String symbol, @Required BigInteger initialAmount, @Required int decimals, @Required BigDecimal price, @Required int awardingCycle,
                @Required BigDecimal minimumDepositNULS, @Required int minimumLocked,String rewardHalvingCycle, String maximumDepositAddressCount, String[] receiverAddress, long[] receiverAmount) {
        super(name, symbol, initialAmount, decimals,receiverAddress,receiverAmount);
        // 检查 price 小数位不得大于decimals
        require(price.compareTo(BigDecimal.ZERO)>0,"价格应该大于0");
        require(checkMaximumDecimals(price, decimals), "最多" + decimals + "位小数");
        require(minimumLocked>0,"最短锁定区块值应该大于0");
        require(awardingCycle>0,"奖励发放周期应该大于0");
        int rewardHalvingCycleForInt=0;
        int maximumDepositAddressCountForInt=0;
        if(rewardHalvingCycle!=null&&rewardHalvingCycle.trim().length()>0){
            require(canConvertNumeric(rewardHalvingCycle.trim(),String.valueOf(Integer.MAX_VALUE)),"奖励减半周期输入不合法，应该输入小于2147483647的数字字符");
            rewardHalvingCycleForInt=Integer.parseInt(rewardHalvingCycle.trim());
            require(rewardHalvingCycleForInt>=0,"奖励减半周期应该大于等于0");
        }
        if(maximumDepositAddressCount!=null&&maximumDepositAddressCount.trim().length()>0){
            require(canConvertNumeric(maximumDepositAddressCount.trim(),String.valueOf(Integer.MAX_VALUE)),"最低抵押数量输入不合法，应该输入小于2147483647的数字字符");
            maximumDepositAddressCountForInt=Integer.parseInt(maximumDepositAddressCount.trim());
            require(maximumDepositAddressCountForInt>=0,"最低抵押数量应该大于等于0");
        }
        this.createHeight = Block.number();
        this.totalDeposit = BigInteger.ZERO;
        this.totalDepositAddressCount = 0;
        this.initialPrice = price;
        this.awardingCycle = awardingCycle;
        this.rewardHalvingCycle = rewardHalvingCycleForInt;
        this.minimumDeposit = toNa(minimumDepositNULS);
        this.minimumLocked = minimumLocked;
        this.maximumDepositAddressCount = maximumDepositAddressCountForInt;
        BigInteger  receiverTotalAmount=BigInteger.ZERO;
        if(receiverAddress!=null && receiverAmount!=null){
            Address[] receiverAddr= convertStringToAddres(receiverAddress);
            //给接收者地址空投Token
            for(int i=0;i<receiverAddress.length;i++){
                if(receiverAddress[i].equals(Msg.sender().toString())){
                    continue;
                }
                AirdropperInfo info = new AirdropperInfo();
                info.setReceiverAddress(receiverAddress[i]);
                BigInteger receiverSupply = BigInteger.valueOf(receiverAmount[i]).multiply(BigInteger.TEN.pow(decimals));
                info.setAirdropperAmount(receiverSupply);
                ariDropperInfos.add(info);
                addBalance(receiverAddr[i],receiverSupply);
                emit(new TransferEvent(null, receiverAddr[i], receiverSupply));
                receiverTotalAmount=receiverTotalAmount.add(BigInteger.valueOf(receiverAmount[i]));
            }
        }

        BigInteger canInitialAmount=initialAmount.subtract(receiverTotalAmount);
        BigInteger initialCreaterSupply = canInitialAmount.multiply(BigInteger.TEN.pow(decimals));
        addBalance(Msg.sender(),initialCreaterSupply);
        emit(new TransferEvent(null, Msg.sender(), initialCreaterSupply));

        if(initialAmount.compareTo(receiverTotalAmount)>=0){
            AirdropperInfo info = new AirdropperInfo();
            info.setReceiverAddress(Msg.sender().toString());
            info.setAirdropperAmount(initialAmount.subtract(receiverTotalAmount).multiply(BigInteger.TEN.pow(decimals)));
            ariDropperInfos.add(info);
        }
    }
    /**
     *为自己抵押获取Token
     * @return
     */
    @Payable
    public void depositForOwn() {
        String userStr = Msg.sender().toString();
        DepositInfo info =depositUsers.get(userStr);
        if(info==null) {
            if (maximumDepositAddressCount > 0) {
                require(totalDepositAddressCount + 1 <= maximumDepositAddressCount, "超过最大抵押地址数量");
            }
            info =new DepositInfo();
            depositUsers.put(userStr,info);
            totalDepositAddressCount += 1;
        }
        BigInteger value = Msg.value();
        long currentHeight =Block.number();
        require(value.compareTo(minimumDeposit) >= 0, "未达到最低抵押值:"+minimumDeposit);
        long depositNumber =NUMBER++; //抵押编号要优化，可能同一个区块多个抵押

        DepositDetailInfo detailInfo = new DepositDetailInfo();
        detailInfo.setDepositAmount(value);
        detailInfo.setDepositHeight(currentHeight);
        detailInfo.setMiningAddress(userStr);
        detailInfo.setDepositNumber(depositNumber);
        info.setDepositorAddress(userStr);
        info.getDepositDetailInfos().put(depositNumber,detailInfo);
        info.setDepositTotalAmount(info.getDepositTotalAmount().add(value));
        info.setDepositCount(info.getDepositCount()+1);

        //将抵押数加入队列中
       this.putDepositToMap(value,currentHeight);


        //初始化挖矿信息
        initMingInfo(currentHeight,userStr,userStr,depositNumber);
        totalDeposit = totalDeposit.add(value);
        emit(new DepositInfoEvent(info));
    }

    /**
     *为他人抵押挖取Token
     * @param miningAddress  指定挖出Token的接受地址
     * @return
     */
    @Payable
    public void depositForOther(@Required Address miningAddress) {
        String userStr = Msg.sender().toString();
        DepositInfo info =depositUsers.get(userStr);
        if(info==null){
            if(maximumDepositAddressCount>0){
                require(totalDepositAddressCount + 1 <= maximumDepositAddressCount, "超过最大抵押地址数量");
            }
            info =new DepositInfo();
            depositUsers.put(userStr,info);
            totalDepositAddressCount += 1;
        }

        BigInteger value = Msg.value();
        require(value.compareTo(minimumDeposit) >= 0, "未达到最低抵押值:"+minimumDeposit);
        long depositNumber =NUMBER++;
        long currentHeight =Block.number();
        DepositDetailInfo detailInfo = new DepositDetailInfo();
        detailInfo.setDepositAmount(value);
        detailInfo.setDepositHeight(currentHeight);
        detailInfo.setMiningAddress(miningAddress.toString());
        detailInfo.setDepositNumber(depositNumber);
        info.setDepositorAddress(userStr);
        info.getDepositDetailInfos().put(depositNumber,detailInfo);
        info.setDepositTotalAmount(info.getDepositTotalAmount().add(value));
        info.setDepositCount(info.getDepositCount()+1);

        //将抵押数加入队列中
        this.putDepositToMap(value,currentHeight);

        //初始化挖矿信息
        initMingInfo(currentHeight,miningAddress.toString(),userStr,depositNumber);
        totalDeposit = totalDeposit.add(value);
        emit(new DepositInfoEvent(info));
    }

    /**
     * 退出抵押挖矿，当抵押编号为0时退出全部抵押
     * @param number 抵押编号
     * @return
     */
    public void quit(String number) {
        long currentHeight =Block.number();
        long depositNumber=0;
        if(number!=null&&number.trim().length()>0){
            require(canConvertNumeric(number.trim(),String.valueOf(Long.MAX_VALUE)),"抵押编号输入不合法，应该输入数字字符");
            depositNumber= Long.valueOf(number.trim());
        }
        Address user = Msg.sender();
        DepositInfo depositInfo =getDepositInfo(user.toString());
        // 发放奖励
        this.receive(depositInfo);
        BigInteger deposit=BigInteger.ZERO;
        MiningInfo miningInfo;

        //表示退出全部的抵押
        if(depositNumber==0){
            miningInfo =mingUsers.get(depositInfo.getDepositorAddress());
            if(miningInfo==null){
                miningInfo =new MiningInfo();
            }
            long result= checkAllDepositLocked(depositInfo);
            require(result == -1, "挖矿的NULS没有全部解锁" );
            deposit=depositInfo.getDepositTotalAmount();
            Map<Long,DepositDetailInfo> depositDetailInfos=depositInfo.getDepositDetailInfos();
            delMingInfo(depositDetailInfos);
            //从队列中退出抵押金额
            for(Long key :depositDetailInfos.keySet()){
                DepositDetailInfo detailInfo=  depositDetailInfos.get(key);
                this.quitDepositToMap(detailInfo.getDepositAmount(),currentHeight,detailInfo.getDepositHeight());
            }
            depositInfo.clearDepositDetailInfos();
        }else{
            //退出某一次抵押
            DepositDetailInfo detailInfo =depositInfo.getDepositDetailInfoByNumber(depositNumber);
            long unLockedHeight = checkDepositLocked(detailInfo);
            require(unLockedHeight == -1, "挖矿锁定中, 解锁高度是 " + unLockedHeight);
            //删除挖矿信息
            miningInfo =mingUsers.get(detailInfo.getMiningAddress());
            miningInfo.removeMiningDetailInfoByNumber(depositNumber);
            if(miningInfo.getMiningDetailInfos().size()==0){
                mingUsers.remove(detailInfo.getMiningAddress());
            }
            depositInfo.removeDepositDetailInfoByNumber(depositNumber);
            // 退押金
            deposit = detailInfo.getDepositAmount();
            depositInfo.setDepositTotalAmount(depositInfo.getDepositTotalAmount().subtract(deposit));
            depositInfo.setDepositCount(depositInfo.getDepositCount()-1);
            //从队列中退出抵押金额
            this.quitDepositToMap(deposit,currentHeight,detailInfo.getDepositHeight());
        }
        totalDeposit = totalDeposit.subtract(deposit);

        if(depositInfo.getDepositDetailInfos().size()==0){
            totalDepositAddressCount -= 1;
            //TODO 退出后是否保留该账户的挖矿记录
            depositUsers.remove(user.toString());
        }
        Msg.sender().transfer(deposit);

        emit(new MiningInfoEvent(miningInfo));
    }

    /**
     *  领取奖励,领取为自己抵押挖矿的Token
     */
    public void receiveAwards() {
        Address user = Msg.sender();
        MiningInfo miningInfo = mingUsers.get(user.toString());
        require(miningInfo != null, "没有为自己抵押挖矿的挖矿信息");
        DepositInfo depositInfo = getDepositInfo(user.toString());
        this.receive(depositInfo);
        emit(new MiningInfoEvent(miningInfo));
    }

    /**
     * 由挖矿接收地址发起领取奖励;当抵押用户为其他用户做抵押挖矿时，接收token用户可以发起此方法
     * @return
     */
    public void receiveAwardsForMiningAddress(){
        List<String> alreadyReceive = new ArrayList<String>();
        Address user = Msg.sender();
        MiningInfo info = mingUsers.get(user.toString());
        require(info != null, "没有替"+user.toString()+"用户抵押挖矿的挖矿信息");
        Map<Long,MiningDetailInfo> detailInfos=info.getMiningDetailInfos();
        for (Long key : detailInfos.keySet()) {
            MiningDetailInfo detailInfo = detailInfos.get(key);
            if(!alreadyReceive.contains(detailInfo.getDepositorAddress())){
                DepositInfo depositInfo = getDepositInfo(detailInfo.getDepositorAddress());
                this.receive(depositInfo);
                alreadyReceive.add(detailInfo.getDepositorAddress());
            }
        }
        emit(new MiningInfoEvent(info));
    }

    /**
     *  查找用户挖矿信息
     */
    @View
    public MiningInfo getMingInfo(@Required Address address) {
        return getMiningInfo(address.toString());
    }

    /**
     * 查找用户的抵押信息
     * @return
     */
    @View
    public DepositInfo getDepositInfo(@Required Address address){
        return getDepositInfo(address.toString());
    }

    /**
     * 获取空投信息
     * @return
     */
    @View
    public List<AirdropperInfo> getAirdropperInfo(){
        return ariDropperInfos;
    }

    /**
     *  当前价格
     */
    @View
    public String currentPrice() {
        int size=totalDepositList.size();
        if(size>0){
            RewardCycleInfo cycleInfoTmp=totalDepositList.get(size-1);
            String amount=cycleInfoTmp.getDepositAmount().toString();
            if(amount.equals("0")){
                return "Unknown";
            }
            BigDecimal big_amount=new  BigDecimal(amount);
            BigDecimal  currentPrice =cycleInfoTmp.getCurrentPrice().divide(big_amount,decimals(),BigDecimal.ROUND_DOWN);
            return  currentPrice.toPlainString() + " " + name() + "/NULS .";
        }else{
            return "Unknown";
        }

    }

    /**
     *单价的精度不能超过定义的精度
     * @param price 单价
     * @param decimals 精度
     * @return
     */
    private static boolean checkMaximumDecimals(BigDecimal price, int decimals) {
        BigInteger a = price.movePointRight(decimals).toBigInteger().multiply(BigInteger.TEN);
        BigInteger b = price.movePointRight(decimals + 1).toBigInteger();
        if(a.compareTo(b) != 0) {
            return false;
        }
        return true;
    }


    /**
     * 根据挖矿地址从队列中获取挖矿信息
     * @param userStr
     * @return
     */
    private MiningInfo getMiningInfo(String userStr) {
        MiningInfo miningInfo = mingUsers.get(userStr);
        require(miningInfo != null, "没有为此用户挖矿的挖矿信息");
        return miningInfo;
    }

    /**
     * 根据抵押地址从队列中获取抵押信息
     * @param userStr
     * @return
     */
    private DepositInfo getDepositInfo(String userStr) {
        DepositInfo depositInfo = depositUsers.get(userStr);
        require(depositInfo != null, "此用户未参与抵押");
        return depositInfo;
    }

    /**
     * 检查抵押是否在锁定中
     * @param depositInfo
     * @return
     */
    private long checkAllDepositLocked(DepositInfo depositInfo) {
        long result;
        Map<Long,DepositDetailInfo> infos =depositInfo.getDepositDetailInfos();
        for (Long key : infos.keySet()) {
            result =checkDepositLocked(infos.get(key));
            if(result!=-1){
                return result;
            }
        }
        return -1;
    }

    /**
     * 检查抵押是否在锁定中
     * @param detailInfo
     * @return
     */
    private long checkDepositLocked(DepositDetailInfo detailInfo) {
        long currentHeight = Block.number();
        long unLockedHeight = detailInfo.getDepositHeight() + minimumLocked + 1;
        if(unLockedHeight > currentHeight) {
            // 锁定中
            return unLockedHeight;
        }
        //已解锁
        return -1;
    }


    /**
     * 领取奖励
     * @param depositInfo
     * @return 返回请求地址的挖矿信息
     */
    private void receive(DepositInfo depositInfo) {
        Map<String,BigInteger> mingResult= new HashMap<String, BigInteger>();
        // 奖励计算, 计算每次挖矿的高度是否已达到奖励减半周期的范围，若达到，则当次奖励减半，以此类推
        BigInteger thisMining = this.calcMining(depositInfo,mingResult);
       Set<String> set = new HashSet<String>(mingResult.keySet());
        for(String address:set){
            Address user = new Address(address);
            BigInteger mingValue= mingResult.get(address);
            addBalance(user, mingValue);
            emit(new TransferEvent(null, user, mingValue));
        }

        this.setTotalSupply(this.getTotalSupply().add(thisMining));
    }


    /**
     * 计算奖励数额
     * @param depositInfo
     * @param mingResult
     * @return
     */
    private BigInteger calcMining(DepositInfo depositInfo,Map<String,BigInteger> mingResult) {
        BigInteger mining = BigInteger.ZERO;
        long currentHeight = Block.number();
        this.putDepositToMap(BigInteger.ZERO,currentHeight);
        BigDecimal currentPrice=this.calcMiningPrice(currentHeight);
        this.moveLastDepositToCurrentCycle(currentHeight,currentPrice);
      //  BigDecimal currentPrice = calcMiningPricePowDecimals(currentHeight);
        Map<Long,DepositDetailInfo> detailInfos=depositInfo.getDepositDetailInfos();
        for (Long key : detailInfos.keySet()) {
            DepositDetailInfo detailInfo = detailInfos.get(key);
            BigInteger mining_tmp=BigInteger.ZERO;
            MiningInfo miningInfo = getMiningInfo(detailInfo.getMiningAddress());
            MiningDetailInfo mingDetailInfo = miningInfo.getMiningDetailInfoByNumber(detailInfo.getDepositNumber());
            long nextStartMiningHeight = mingDetailInfo.getNextStartMiningHeight();
            int startCycle= Integer.parseInt(String.valueOf(nextStartMiningHeight-this.createHeight))/this.awardingCycle;
            BigDecimal depositAmountNULS = toNuls(detailInfo.getDepositAmount());
            BigDecimal sumPrice=this.calcPriceBetweenCycle(startCycle);
            mining_tmp = mining_tmp.add(depositAmountNULS.multiply(sumPrice).scaleByPowerOfTen(decimals()).toBigInteger());
            int roundCount=this.calcRewardCycle(currentHeight);
            nextStartMiningHeight=nextStartMiningHeight+awardingCycle*roundCount;

            mingDetailInfo.setMiningAmount(mingDetailInfo.getMiningAmount().add(mining_tmp));
            mingDetailInfo.setMiningCount(mingDetailInfo.getMiningCount()+roundCount);
            mingDetailInfo.setNextStartMiningHeight(nextStartMiningHeight);
            miningInfo.setTotalMining(miningInfo.getTotalMining().add(mining_tmp));
            miningInfo.setReceivedMining(miningInfo.getReceivedMining().add(mining_tmp));

            if(mingResult.containsKey(mingDetailInfo.getReceiverMiningAddress())){
                mining_tmp=mingResult.get(mingDetailInfo.getReceiverMiningAddress()).add(mining_tmp);
            }
            mingResult.put(mingDetailInfo.getReceiverMiningAddress(),mining_tmp);
            mining = mining.add(mining_tmp);
        }
        return mining;
    }


    /**
     * 删除挖矿信息
     * @param infos
     */
    private void delMingInfo(Map<Long,DepositDetailInfo> infos){
        for (Long key : infos.keySet()) {
            DepositDetailInfo detailInfo = infos.get(key);
            MiningInfo miningInfo =mingUsers.get(detailInfo.getMiningAddress());
            miningInfo.removeMiningDetailInfoByNumber(detailInfo.getDepositNumber());
            if(miningInfo.getMiningDetailInfos().size()==0){
                mingUsers.remove(detailInfo.getMiningAddress());
            }
        }
    }

    /**
     * 初始化挖矿信息
     * @param miningAddress
     * @param depositorAddress
     * @param depositNumber
     * @return
     */
    private void initMingInfo(long currentHeight,String miningAddress ,String depositorAddress,long depositNumber ){
        MiningDetailInfo mingDetailInfo = new MiningDetailInfo(miningAddress,depositorAddress,depositNumber);
        mingDetailInfo.setNextStartMiningHeight(currentHeight);
        MiningInfo mingInfo =  mingUsers.get(miningAddress);
        if(mingInfo==null){//该Token地址为第一次挖矿
            mingInfo =  new MiningInfo();
            mingInfo.getMiningDetailInfos().put(depositNumber,mingDetailInfo);
            mingUsers.put(miningAddress,mingInfo);
        }else{
            mingInfo.getMiningDetailInfos().put(depositNumber,mingDetailInfo);
        }
    }

    /**
     * 根据当前高度计算对应的单价
     * @param currentHeight
     * @return
     */
    private BigDecimal calcMiningPrice(long currentHeight) {
        if(this.rewardHalvingCycle==0){
            return  this.initialPrice;
        }
        //减半周期
        int rewardHalvingRound= Integer.parseInt(String.valueOf(currentHeight-this.createHeight-1))/this.rewardHalvingCycle;
        if(rewardHalvingRound==0){
            return  this.initialPrice;
        }else{
            //当前周期的单价
           return calcHalvingPrice(rewardHalvingRound);
        }
    }

    /**
     * 计算减半周期的单价,减半周期最大允许90次
     * @param rewardHalvingRound
     * @return
     */
    private BigDecimal calcHalvingPrice(int rewardHalvingRound){
        BigDecimal round=BigDecimal.ZERO;
        int count=rewardHalvingRound/30;
        BigDecimal base=new BigDecimal(2<<29);
        if(count==0||rewardHalvingRound==30){
            round=new BigDecimal(2<<rewardHalvingRound-1);
        }else if(count==1||rewardHalvingRound==60){
            round=new BigDecimal(2<<rewardHalvingRound-31);
            round =base.multiply(round);
        }else if(count==2||rewardHalvingRound==90){
            round=new BigDecimal(2<<rewardHalvingRound-61);
            round =base.multiply(base).multiply(round);
        }else{
            require(false, "减半周期次数最大允许90次,目前已经达到"+rewardHalvingRound+"次");
            return BigDecimal.ZERO;
        }
        return this.initialPrice.divide(round,decimals(),BigDecimal.ROUND_DOWN);

    }

    /**
     * 在加入抵押时将抵押金额加入队列中
     * @param depositValue
     * @param currentHeight
     */
    private void putDepositToMap(BigInteger depositValue,long currentHeight){
        BigDecimal currentPrice=this.calcMiningPrice(currentHeight);
        int rewardingCycle= this.calcRewardCycle(currentHeight);
        int rewardHalvingRound=this.calcRewardHalvingCycle(currentHeight);
        if(rewardHalvingRound>0){
            //将减半周期对应高度的抵押总额加入队列
            boolean isSame=this.isSameRewardHalvingCycle(currentHeight,this.lastCalcHeight);
            if(!isSame){
                long  rewardHalvingHeight =rewardHalvingCycle*this.rewardHalvingCycle+this.createHeight;
                this.moveLastDepositToCurrentCycle(rewardHalvingHeight,currentPrice);
            }
        }

        int alreadyTotalDepositIndex=totalDepositIndex.get(rewardingCycle+1);
        RewardCycleInfo cycleInfo = new RewardCycleInfo();
        if(alreadyTotalDepositIndex==0){
            if(lastCalcCycle==0){
                cycleInfo.setDepositAmount(depositValue);
                cycleInfo.setRewardingCylce(rewardingCycle+1);
                cycleInfo.setDifferCycleValue(1);
                cycleInfo.setCurrentPrice(currentPrice);
                totalDepositList.add(cycleInfo);
            }else{
                RewardCycleInfo lastCycleInfo= totalDepositList.get(totalDepositIndex.get(lastCalcCycle));
                cycleInfo.setDepositAmount(depositValue.add(lastCycleInfo.getDepositAmount()));
                cycleInfo.setRewardingCylce(rewardingCycle+1);
                cycleInfo.setDifferCycleValue(rewardingCycle-lastCycleInfo.getRewardingCylce());
                cycleInfo.setCurrentPrice(currentPrice);
                totalDepositList.add(cycleInfo);
            }
            totalDepositIndex.put(rewardingCycle+1,totalDepositList.size()-1);
            lastCalcCycle=rewardingCycle+1;
        }else{
            RewardCycleInfo cycleInfoTmp= totalDepositList.get(alreadyTotalDepositIndex);
            cycleInfoTmp.setCurrentPrice(currentPrice);
            cycleInfoTmp.setDepositAmount(depositValue.add(cycleInfoTmp.getDepositAmount()));
        }
        this.lastCalcHeight=rewardingCycle*this.awardingCycle+this.createHeight;
    }

    /**
     * 抵押数额没有变动的情况下，新增当前奖励周期下的抵押数
     * @param currentHeight
     * @param currentPrice
     */
    private void moveLastDepositToCurrentCycle(long currentHeight,BigDecimal currentPrice){
        RewardCycleInfo cycleInfo = new RewardCycleInfo();
        int rewardingCycle= this.calcRewardCycle(currentHeight);
        int alreadTotalDepositIndex=totalDepositIndex.get(rewardingCycle);
        //当前奖励周期还未统计抵押总数
        if(alreadTotalDepositIndex==0){
            if(lastCalcCycle!=0){
                RewardCycleInfo cycleInfoTmp= totalDepositList.get(totalDepositIndex.get(lastCalcCycle));
                cycleInfo.setDepositAmount(cycleInfoTmp.getDepositAmount());
                cycleInfo.setRewardingCylce(rewardingCycle);
                cycleInfo.setDifferCycleValue(rewardingCycle-cycleInfoTmp.getRewardingCylce());
                cycleInfo.setCurrentPrice(currentPrice);
                totalDepositList.add(cycleInfo);
                totalDepositIndex.put(rewardingCycle,totalDepositList.size()-1);
            }else{
                //第一次进行抵押操作
                cycleInfo.setDepositAmount(BigInteger.ZERO);
                cycleInfo.setRewardingCylce(rewardingCycle);
                cycleInfo.setDifferCycleValue(1);
                cycleInfo.setCurrentPrice(currentPrice);
                totalDepositList.add(cycleInfo);
                totalDepositIndex.put(rewardingCycle,totalDepositList.size()-1);
            }
        }

    }
    /**
     * 退出抵押时从队列中退出抵押金额
     * @param depositValue
     * @param currentHeight
     * @param depositHeight
     */
    private void quitDepositToMap(BigInteger depositValue,long currentHeight ,long depositHeight){
        int currentCycle= this.calcRewardCycle(currentHeight);
        int depositCycle=this.calcRewardCycle(depositHeight);
        if(currentCycle ==depositCycle){
            //加入抵押和退出抵押在同一个奖励周期
            RewardCycleInfo cycleInfoTmp= totalDepositList.get(totalDepositIndex.get(currentCycle+1));
            cycleInfoTmp.setDepositAmount(cycleInfoTmp.getDepositAmount().subtract(depositValue));
        }else{
            //加入抵押和退出抵押不在同一个奖励周期
            RewardCycleInfo cycleInfoTmp= totalDepositList.get(totalDepositIndex.get(currentCycle));
            cycleInfoTmp.setDepositAmount(cycleInfoTmp.getDepositAmount().subtract(depositValue));
        }
    }

    /**
     * 计算从开始
     * @param startCycle
     * @return
     */
    private BigDecimal calcPriceBetweenCycle(int startCycle){
        BigDecimal sumPriceForRegin =BigDecimal.ZERO;
        int startIndex = totalDepositIndex.get(startCycle);
        for(int i=startIndex;i<totalDepositList.size();i++){
            RewardCycleInfo cycleInfoTmp=totalDepositList.get(i);
            String amount=cycleInfoTmp.getDepositAmount().toString();
            BigDecimal big_amount=new  BigDecimal(amount);
            BigDecimal  sumPrice =cycleInfoTmp.getCurrentPrice().divide(big_amount,decimals(),BigDecimal.ROUND_DOWN).multiply(BigDecimal.valueOf(cycleInfoTmp.getDifferCycleValue()));
            sumPriceForRegin =sumPriceForRegin.add(sumPrice);
        }
        return sumPriceForRegin;
    }

    /**
     * 计算当前高度的奖励周期
     * @param currentHeight
     * @return
     */
    private int calcRewardCycle(long currentHeight){
        return Integer.parseInt(String.valueOf(currentHeight-this.createHeight))/this.awardingCycle;
    }

    /**
     * 计算奖励减半周期
     * @param currentHeight
     * @return
     */
    private int calcRewardHalvingCycle(long currentHeight){
        int halvingCycle=0;
        if(this.rewardHalvingCycle>0){
            halvingCycle = Integer.parseInt(String.valueOf(currentHeight-this.createHeight))/this.rewardHalvingCycle;
        }
        return halvingCycle;
    }

    /**
     * 比较两个高度值是否在同一个减半周期
     * @param currentHeight
     * @param lastHeight
     * @return
     */
    private boolean isSameRewardHalvingCycle(long currentHeight,long lastHeight){
        if(this.rewardHalvingCycle>0){
            int currentrewardHalvingCycle= Integer.parseInt(String.valueOf(currentHeight-this.createHeight))/this.rewardHalvingCycle;
            int lastrewardHalvingCycle= Integer.parseInt(String.valueOf(lastHeight-this.createHeight))/this.rewardHalvingCycle;
            if(currentrewardHalvingCycle==lastrewardHalvingCycle){
                return true;
            }else{
                return false;
            }
        }else{
            return true;
        }

    }


    /**
     *  初始价格
     */
    @View
    public String initialPrice() {
        return initialPrice.toPlainString() + " " + name() + "/ x NULS";
    }

    @View
    public long createHeight() {
        return createHeight;
    }

    @View
    public int totalDepositAddressCount() {
        return totalDepositAddressCount;
    }

    @View
    public String totalDeposit() {
        return toNuls(totalDeposit).toPlainString();
    }

    @View
    public long awardingCycle() {
        return this.awardingCycle;
    }
    @View
    public long rewardHalvingCycle() {
        return this.rewardHalvingCycle;
    }
    @View
    public BigInteger minimumDeposit() {
        return this.minimumDeposit;
    }
    @View
    public int minimumLocked() {
        return this.minimumLocked;
    }
    @View
    public int maximumDepositAddressCount() {
        return this.maximumDepositAddressCount;
    }
}
