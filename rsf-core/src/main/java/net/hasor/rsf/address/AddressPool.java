/*
 * Copyright 2008-2009 the original 赵永春(zyc@hasor.net).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.rsf.address;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.more.util.StringUtils;
import org.more.util.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.hasor.core.Environment;
import net.hasor.rsf.BindCenter;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.address.route.flowcontrol.random.RandomFlowControl;
import net.hasor.rsf.address.route.flowcontrol.speed.SpeedFlowControl;
import net.hasor.rsf.address.route.flowcontrol.unit.UnitFlowControl;
import net.hasor.rsf.address.route.rule.Rule;
import net.hasor.rsf.address.route.rule.RuleParser;
import net.hasor.rsf.rpc.context.RsfEnvironment;
/**
 * 服务地址池
 * @version : 2014年9月12日
 * @author 赵永春(zyc@hasor.net)
 */
public class AddressPool implements Runnable {
    private static final String                        CharsetName = "UTF-8";
    protected Logger                                   logger      = LoggerFactory.getLogger(getClass());
    private final RsfSettings                          rsfSettings;
    private final RsfEnvironment                       rsfEnvironment;
    private final ConcurrentMap<String, AddressBucket> addressPool;                                      //服务地址池Map.
    private final String                               unitName;                                         //本机所处单元.
    //
    private final AddressCacheResult                   rulerCache;
    private RuleParser                                 ruleParser;
    private volatile FlowControlRef                    flowControlRef;                                   //默认流控规则引用
    private final Object                               poolLock;
    private final Thread                               timer;
    //
    //
    //
    public void run() {
        long refreshCacheTime = this.rsfSettings.getRefreshCacheTime();
        long nextCheckSavePoint = 0;
        while (true) {
            try {
                Thread.sleep(refreshCacheTime);
                logger.info("refreshCacheTime({}) timeup -> refreshCache.", refreshCacheTime);
                this.refreshCache();
                //
                if (nextCheckSavePoint < System.currentTimeMillis()) {
                    nextCheckSavePoint = System.currentTimeMillis() + (1 * 60 * 60 * 1000);//1小时
                    try {
                        this.saveAddress();
                    } catch (IOException e) {
                        logger.error("saveAddress error {} -> {}", e.getMessage(), e);
                        e.printStackTrace();
                    }
                }
            } catch (InterruptedException e) {
                /**/
            }
        }
    }
    /**保存地址列表到zip流中(每小时保存一次)。*/
    public void saveAddress() throws IOException {
        String workDir = rsfEnvironment.evalString("%" + Environment.WORK_HOME + "%/rsf/");
        File writeFile = new File(workDir, "address-" + System.currentTimeMillis() + ".zip");
        try {
            writeFile.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(writeFile);
            ZipOutputStream zipStream = new ZipOutputStream(fos);
            synchronized (this.poolLock) {
                for (AddressBucket bucket : this.addressPool.values()) {
                    if (bucket != null) {
                        bucket.saveTo(zipStream, CharsetName);
                    }
                }
            }
            zipStream.flush();
            zipStream.close();
            //
            FileWriter fw = new FileWriter(new File(workDir, "address.index"), false);
            fw.write(writeFile.getName());
            fw.flush();
            fw.close();
        } catch (IOException e) {
            logger.error("saveAddress to {} error -> {}", writeFile, e);
            throw e;
        }
    }
    /**从保存的地址本中恢复数据。*/
    public void readAddress(AddressBucket bucker) {
        try {
            String workDir = rsfEnvironment.evalString("%" + Environment.WORK_HOME + "%/rsf/");
            File indexFile = new File(workDir, "address.index");
            if (!indexFile.exists()) {
                return;
            }
            String index = FileUtils.readFileToString(new File(workDir, "address.index"), CharsetName);
            File readFile = new File(workDir, index);
            //
            if (readFile.exists()) {
                ZipFile zipFile = new ZipFile(readFile);
                bucker.readFrom(zipFile, CharsetName);
            } else {
                logger.error("file {} is not exists.", readFile);
            }
        } catch (Throwable e) {
            logger.error("readAddress to {} error -> {}", bucker.getServiceID(), e);
        }
    }
    //
    public AddressPool(String unitName, BindCenter bindCenter, RsfEnvironment rsfEnvironment) {
        logger.info("init AddressPool unitName = " + unitName);
        //
        this.rsfEnvironment = rsfEnvironment;
        this.rsfSettings = rsfEnvironment.getSettings();
        this.addressPool = new ConcurrentHashMap<String, AddressBucket>();
        this.unitName = unitName;
        this.rulerCache = new AddressCacheResult(this, bindCenter);
        this.ruleParser = new RuleParser(rsfSettings);
        this.poolLock = new Object();
        this.timer = new Thread(this);
        this.timer.setName("RSF-AddressPool-RefreshCache-Thread");
        this.timer.setDaemon(true);
        logger.info("start refreshCacheTime[{}] Thread.", this.timer.getName());
        this.timer.start();
        this.flowControlRef = FlowControlRef.defaultRef(rsfSettings);
        this.rulerCache.reset();
    }
    //
    /**获取本机所属单元*/
    public String getUnitName() {
        return this.unitName;
    }
    /**
     * 所有服务地址快照功能，该接口获得的数据不可以进行写操作。通过这个接口可以获得到，此刻地址池中所有服务的
     * <ol>
     * <li>原始服务地址列表，以serviceID_ALL作为key</li>
     * <li>本单元服务地址列表，以serviceID_UNIT作为key</li>
     * <li>不可用服务地址列表，以serviceID_INVALID作为key</li>
     * <li>所有可用服务地址列表，以serviceID作为key</li>
     * <ol>
     * 并不是单元化的列表中是单元化规则计算的结果,规则如果失效单元化列表中讲等同于 all
     */
    public Map<String, List<InterAddress>> allServicesSnapshot() {
        Map<String, List<InterAddress>> snapshot = new HashMap<String, List<InterAddress>>();
        synchronized (this.poolLock) {
            for (String key : this.addressPool.keySet()) {
                AddressBucket bucket = this.addressPool.get(key);
                snapshot.put(key + "_ALL", bucket.getAllAddresses());
                snapshot.put(key + "_UNIT", bucket.getLocalUnitAddresses());
                snapshot.put(key + "_INVALID", bucket.getInvalidAddresses());
                snapshot.put(key, bucket.getAvailableAddresses());
            }
        }
        return snapshot;
    }
    /**返回地址池中所有已注册的服务列表*/
    public Collection<String> listServices() {
        Set<String> duplicate = new HashSet<String>();
        synchronized (this.poolLock) {
            duplicate.addAll(this.addressPool.keySet());
        }
        return duplicate;
    }
    //
    //
    /**新增地址支持动态新增,在地址池中标识这个Service的AddressBucket key为bindInfo.getBindID()*/
    public void newAddress(RsfBindInfo<?> bindInfo, Collection<URI> newHostList) {
        //1.AddressBucket
        String serviceID = bindInfo.getBindID();
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket == null) {
            /*在并发情况下,invalidAddress可能正打算读取AddressBucket,因此要锁住poolLock*/
            synchronized (this.poolLock) {
                int invalidTryCount = this.rsfSettings.getInvalidTryCount();
                AddressBucket newBucket = new AddressBucket(serviceID, this.unitName, invalidTryCount);
                bucket = this.addressPool.putIfAbsent(serviceID, newBucket);
                if (bucket == null) {
                    bucket = newBucket;
                    this.readAddress(newBucket);
                }
                logger.info("newBucket {}", bucket);
            }
        }
        //2.新增服务
        bucket.newAddress(newHostList);
        logger.info("newAddress: {}", newHostList);
        bucket.refreshAddress();//局部更新
        this.rulerCache.reset();
    }
    /**将地址置为失效的。*/
    public void invalidAddress(URI uri) {
        this.invalidAddress(new InterAddress(uri));
    }
    /**将地址置为失效的。*/
    public void invalidAddress(InterAddress address) {
        /*在并发情况下,newAddress可能正在创建AddressBucket,因此要锁住poolLock*/
        synchronized (this.poolLock) {
            for (String bucketKey : this.addressPool.keySet()) {
                AddressBucket bucket = this.addressPool.get(bucketKey);
                if (bucket == null) {
                    return;
                }
                long invalidWaitTime = rsfSettings.getInvalidWaitTime();
                bucket.invalidAddress(address, invalidWaitTime);
                bucket.refreshAddress();
            }
        }
        this.rulerCache.reset();
    }
    /**回收已经发布的服务*/
    public void recoverService(RsfBindInfo<?> bindInfo) {
        /*在并发情况下,newAddress可能正在创建AddressBucket,因此要锁住poolLock*/
        synchronized (this.poolLock) {
            String serviceID = bindInfo.getBindID();
            this.addressPool.remove(serviceID);
        }
        this.rulerCache.reset();
    }
    //
    /**用新的路由规则刷新地址池*/
    public void refreshDefaultFlowControl(String flowControl) throws IOException {
        this.flowControlRef = paselowControl(flowControl);
    }
    /**用新的路由规则刷新地址池*/
    public void refreshFlowControl(String serviceID, String flowControl) throws IOException {
        FlowControlRef flowControlRef = paselowControl(flowControl);
        AddressBucket bucket = this.addressPool.get(serviceID);
        if (bucket != null) {
            bucket.setFlowControlRef(flowControlRef);
        }
        //4.刷新缓存
        this.refreshCache();
    }
    private FlowControlRef paselowControl(String flowControl) {
        if (StringUtils.isBlank(flowControl) || !flowControl.startsWith("<controlSet") || !flowControl.endsWith("</controlSet>")) {
            logger.error("flowControl body format error.");
            return null;
        }
        //
        FlowControlRef flowControlRef = FlowControlRef.defaultRef(rsfSettings);
        //
        //1.提取路由配置
        List<String> ruleBodyList = new ArrayList<String>();
        final String tagNameBegin = "<flowControl";
        final String tagNameEnd = "</flowControl>";
        int beginIndex = 0;
        int endIndex = 0;
        while (true) {
            beginIndex = flowControl.indexOf(tagNameBegin, endIndex);
            endIndex = flowControl.indexOf(tagNameEnd, endIndex + tagNameEnd.length());
            if (beginIndex < 0 || endIndex < 0) {
                break;
            }
            String flowControlBody = flowControl.substring(beginIndex, endIndex + tagNameEnd.length());
            ruleBodyList.add(flowControlBody);
        }
        if (ruleBodyList.isEmpty()) {
            logger.warn("flowControl is empty -> use default settings.");
        }
        //2.解析路由配置
        for (int i = 0; i < ruleBodyList.size(); i++) {
            String controlBody = ruleBodyList.get(i);
            Rule rule = this.ruleParser.ruleSettings(controlBody);
            if (rule == null) {
                continue;
            }
            String simpleName = rule.getClass().getSimpleName();
            logger.info("setup flowControl -> {}.", simpleName);
            /*  */if (rule instanceof UnitFlowControl) {
                flowControlRef.unitFlowControl = (UnitFlowControl) rule; /*单元规则*/
            } else if (rule instanceof RandomFlowControl) {
                flowControlRef.randomFlowControl = (RandomFlowControl) rule;/*选址规则*/
            } else if (rule instanceof SpeedFlowControl) {
                flowControlRef.speedFlowControl = (SpeedFlowControl) rule; /*速率规则*/
            }
        }
        //3.引用切换
        return flowControlRef;
    }
    //
    /**刷新缓存*/
    public void refreshCache() {
        /*在并发情况下,newAddress和invalidAddress可能正在执行,因此要锁住poolLock*/
        synchronized (this.poolLock) {
            Set<String> keySet = this.addressPool.keySet();
            for (String bucketKey : keySet) {
                this.addressPool.get(bucketKey).refreshAddress();//刷新地址计算结果
            }
            this.rulerCache.reset();
        }
    }
    //
    /**轮转获取地址(如果{@link #refreshFlowControl(String)}或{@link #refreshCache()}处在执行期,则该方法会被挂起等待操作完毕.)*/
    public InterAddress nextAddress(RsfBindInfo<?> info, String methodSign, Object[] args) {
        String serviceID = info.getBindID();
        //
        /*并发下不需要保证瞬时的一致性,只要保证最终一致性就好.*/
        AddressBucket bucket = addressPool.get(serviceID);
        if (bucket == null) {
            return null;
        }
        //
        List<InterAddress> addresses = this.rulerCache.getAddressList(info, methodSign, args);
        InterAddress doCallAddress = null;
        //
        FlowControlRef flowControlRef = bucket.getFlowControlRef();
        if (flowControlRef == null) {
            flowControlRef = this.flowControlRef;
        }
        doCallAddress = flowControlRef.randomFlowControl.getServiceAddress(addresses);
        while (true) {
            boolean check = flowControlRef.speedFlowControl.callCheck(info, methodSign, doCallAddress);//QoS
            if (check) {
                break;
            }
        }
        //
        return doCallAddress;
    }
    //
    @Override
    public String toString() {
        return "AddressPool[" + this.unitName + "]";
    }
}