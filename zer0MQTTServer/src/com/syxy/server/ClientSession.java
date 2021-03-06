package com.syxy.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.log4j.Logger;

import com.syxy.Aiohandler.AioReadHandler;
import com.syxy.Aiohandler.AioWriteHandler;
import com.syxy.protocol.ICoderHandler;
import com.syxy.protocol.IDecoderHandler;
import com.syxy.protocol.IProcessHandler;
import com.syxy.protocol.mqttImp.message.Message;
import com.syxy.server.job.KeepAliveJob;
import com.syxy.util.BufferPool;
import com.syxy.util.Constant;
import com.syxy.util.QuartzManager;
import com.syxy.util.coderTool;

/**
 *  客户session，每个连接一个ClientSession对象，用于处理客户请求和响应
 * 
 * @author zer0
 * @version 1.0
 * @date 2015-2-16
 */
public class ClientSession {

	private final static Logger Log = Logger.getLogger(ClientSession.class);
	
	// 定义编码处理器，业务处理器，解码处理器
	private ICoderHandler coderHandler;// 编码处理器
	private IDecoderHandler decoderHandler;// 解码处理器
	private IProcessHandler processHandler;// 业务处理器
	
	private TcpServer socketServer;
	private AsynchronousSocketChannel socketChannel;// 异步socket连结端
	private AioReadHandler readHandler;// 读取处理器
	private AioWriteHandler writeHandler;// 回写处理器	
	
	private ByteBuffer byteBuffer;// 缓冲区
	
	private Object index;// 客户端在索引
	
	private Message msg;//协议对象
	private Map<Object, Object> attributesKeys = new HashMap<Object, Object>();//存放一些客户端需要的属性
	
	public ClientSession(AsynchronousSocketChannel socketChannel, 
			AioReadHandler readHandler, 
			AioWriteHandler writeHandler,
			TcpServer socketServer) {
		this.socketChannel = socketChannel;
		this.readHandler = readHandler;
		this.writeHandler = writeHandler;
		this.socketServer = socketServer;
	}
	
	/**
	 * 把编码，解码和协议处理器注册给client，方便client读数据，写数据
	 * 的时候编码，解码
	 * @param coderHandler
	 * @param decoderHandler
	 * @param processHandler
	 * @author zer0
	 * @version 1.0
	 * @date  2015-2-20
	 */
	public void registeHandler(ICoderHandler coderHandler, IDecoderHandler decoderHandler, IProcessHandler processHandler){
		this.coderHandler = coderHandler;
		this.decoderHandler = decoderHandler;
		this.processHandler = processHandler;
	}
	
	/**
	 * 读请求事件，调用AioReadHandler处理读事件
	 * 
	 * @author zer0
	 * @version 1.0
	 * @date  2015-2-21
	 */
	public void readEvent(){
		try {
			//如果socket通道未关闭，就读数据
			if (this.socketChannel.isOpen()){
//				this.byteBuffer = ByteBuffer.allocate(1024 * 64);   
				this.byteBuffer = BufferPool.getInstance().getBuffer();
	            this.socketChannel.read(this.byteBuffer, this, this.readHandler);    
	        } else {  
	            Log.info("会话被取消或者关闭");  
	        }
		} catch (Exception e) {
			Log.warn(e.getMessage());
			this.close();
		}
	}
	
	/**
	 * 读请求完成后，将得到的缓冲区写入程序的另一个缓冲区，
	 * 如果此缓冲区进行解码处理，以便能识别
	 * 
	 * @return boolean
	 * @author zer0
	 * @version 1.0
	 * @date  2015-2-21
	 */
	public boolean readRequest(){
		Log.info("进行解码处理");	
		boolean returnValue = false;// 返回值	,若数据处理完毕无问题，改为true
		this.byteBuffer.flip();
		
//		String bufData = coderTool.decode(this.byteBuffer).toString();
//		Log.info("读到的数据长度为"+bufData.length()+",是"+bufData);
//		this.byteBuffer.flip();

		this.msg = this.decoderHandler.process(this.byteBuffer);
		this.byteBuffer.clear();
		//使用完缓冲区以后释放
		BufferPool.getInstance().releaseBuffer(this.byteBuffer);
		
		returnValue = true;
		this.readEvent();
		
		return returnValue;
	}

	/**
	 * 关闭socket连接
	 * 
	 * @author zer0
	 * @version 1.0
	 * @date  2015-2-21
	 */
	public void close(){
		try{
			this.socketChannel.close();		
		} catch (ClosedChannelException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}finally{			
			try{
				socketChannel.close();
			}catch(IOException e){
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * 获取客户端IP地址
	 * 
	 * @param String
	 * @author zer0
	 * @version 1.0
	 * @date  2015-2-22
	 */
	public String getIp(){		
		try {
			return this.socketChannel.getRemoteAddress().toString();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	/**
	 * 处理读取的数据，在处理的函数中来调用回写函数，并告诉回写函数该写哪些内容
	 * 
	 * @author zer0
	 * @version 1.0
	 * @date  2015-2-22
	 */
	public void process(){
		try{
			if (msg != null) {
				this.processHandler.process(msg, this);// 业务处理
			}else {
				Log.debug("连接"+this.getIp()+"的msg为空");
			}
		}catch(Exception e){
			e.printStackTrace();
			this.close();
		}
	}
	
	/**
	 * 处理读取到的数据为空的情况
	 * 
	 * @author zer0
	 * @version 1.0
	 * @date  2015-2-22
	 */
	public void processBlankRead(){
		//暂不处理
	}
	
	/**
	 * 对消息类型编码并回写给所有客户端，由协议业务处理类调用
	 * 
	 * @param msg
	 * @param byteBuffer
	 * @author zer0
	 * @version 1.0
	 * @date  2015-3-4
	 */
	public void writeMsgToReqClient(Message msg){
		try {
			ByteBuffer byteBuffer = this.encodeProtocol(msg);
			byteBuffer.flip();
			this.sendMsgToReqClient(byteBuffer);
		} catch (Exception e) {
			Log.error("回写数据给请求者出错，请检查");
			e.printStackTrace();
		}
	}
	
	/**
	 * 对协议进行编码，针对相应消息类型编码
	 * 
	 * @param msg
	 * @return ByteBuffer
	 * @author zer0
	 * @version 1.0
	 * @date  2015-3-4
	 */
	private ByteBuffer encodeProtocol(Message msg) throws IOException{
		if (msg != null) {
			ByteBuffer byteBuffer = this.coderHandler.process(msg);// 协议编码处理
			return byteBuffer;
		}	
		return null;
	}
	
	/**
	 *  处理心跳包动作，超过心跳包时长的2倍时间未接收到心跳包数据，则视为与客户端与服务器失去连接，断开客户连接
	 * 
	 * @param flag
	 * @author zer0
	 * @version 1.0
	 * @date  2015-6-29
	 */
	public void keepAliveHandler(String flag, String clientID){
		int keepAlive = (int)getAttributesKeys(Constant.KEEP_ALIVE);
		Map<String, Object> jobParam = new HashMap<String, Object>();
		jobParam.put("ProtocolProcess", this);
		jobParam.put("clientID", clientID);
		if (flag.equals(Constant.CONNECT_ARRIVE)) {
			QuartzManager.addJob(clientID, "keepAlives", clientID, "keepAlives", KeepAliveJob.class, keepAlive*2, jobParam);
		}else if (flag.equals(Constant.PING_ARRIVE)) {
			QuartzManager.resetJob(clientID, "keepAlives", clientID, "keepAlives", KeepAliveJob.class, keepAlive*2, jobParam);
		}
	}
	
	/**
	 *  回写数据给请求的客户端
	 * 
	 * @param byteBuffer
	 * @author zer0
	 * @version 1.0
	 * @date  2015-3-4
	 */
	private void sendMsgToReqClient(ByteBuffer byteBuffer) throws Exception{
		this.socketChannel.write(byteBuffer, this, this.writeHandler);
	}
	
	public Object getIndex() {
		return index;
	}

	public void setIndex(Object index) {
		this.index = index;
	}

	public ByteBuffer getByteBuffer() {
		return byteBuffer;
	}

	public void setByteBuffer(ByteBuffer byteBuffer) {
		this.byteBuffer = byteBuffer;
	}

	public AsynchronousSocketChannel getSocketChannel() {
		return socketChannel;
	}

	public void setSocketChannel(AsynchronousSocketChannel socketChannel) {
		this.socketChannel = socketChannel;
	}
	
	public Object getAttributesKeys(Object key) {
		return attributesKeys.get(key);
	}

	public void setAttributesKeys(Object key, Object value) {
		attributesKeys.put(key, value);
	}
	
}
