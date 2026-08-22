package com.distressedelk.lumi;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * Code 293 lightweight speaker-comparison helper.
 *
 * This intentionally produces only a soft personalization confidence signal.
 * It is NOT a security biometric and must never replace Lumi's PIN/device-credential
 * authorization for privileged actions.
 */
final class SpeakerVerifier {
    static final class Result {
        final boolean usable;
        final double similarity;
        final int confidence;
        final boolean probableMatch;
        final String detail;
        Result(boolean usable,double similarity,int confidence,boolean probableMatch,String detail){
            this.usable=usable;
            this.similarity=similarity;
            this.confidence=confidence;
            this.probableMatch=probableMatch;
            this.detail=detail;
        }
    }

    private SpeakerVerifier(){}

    static Result compare(File enrolled, File candidate){
        try{
            Features a=features(enrolled);
            Features b=features(candidate);
            if(a==null || b==null) return new Result(false,0,0,false,"Not enough usable speech audio.");
            double cos=cosine(a.vector,b.vector);
            double durPenalty=Math.min(1.0,Math.min(a.seconds,b.seconds)/2.5);
            double score=Math.max(0.0,Math.min(1.0,cos*durPenalty));
            int confidence=(int)Math.round(score*100.0);
            boolean match=score>=0.78 && a.seconds>=1.8 && b.seconds>=1.8;
            return new Result(true,score,confidence,match,
                    String.format(Locale.US,"similarity=%.3f enrolled=%.1fs sample=%.1fs",score,a.seconds,b.seconds));
        }catch(Exception e){
            return new Result(false,0,0,false,e.getClass().getSimpleName()+": "+safe(e.getMessage()));
        }
    }

    private static final class Features {
        final double[] vector;
        final double seconds;
        Features(double[] vector,double seconds){this.vector=vector;this.seconds=seconds;}
    }

    private static Features features(File f)throws Exception{
        Decoded d=decode(f);
        if(d==null || d.samples==null || d.samples.length<8000)return null;
        float[] x=resample(d.samples,d.sampleRate,16000);
        if(x.length<16000)return null;

        // Remove long leading/trailing near-silence.
        int start=0,end=x.length;
        while(start<end && Math.abs(x[start])<0.008f)start++;
        while(end>start && Math.abs(x[end-1])<0.008f)end--;
        if(end-start<12000)return null;
        x=Arrays.copyOfRange(x,start,end);

        // Phrase-dependent but useful acoustic summary: normalized band-energy shape,
        // zero-crossing behavior, and amplitude envelope statistics.
        double[] freq={120,180,260,380,550,800,1150,1650,2350,3300};
        double[] v=new double[freq.length+4];
        double total=0,abs=0,rms=0;
        int zc=0;
        for(int i=0;i<x.length;i++){
            double q=x[i];
            abs+=Math.abs(q);
            rms+=q*q;
            if(i>0 && ((x[i-1]<0 && q>=0)||(x[i-1]>=0 && q<0)))zc++;
        }
        abs/=x.length;
        rms=Math.sqrt(rms/x.length);
        for(int k=0;k<freq.length;k++){
            double p=goertzel(x,16000,freq[k]);
            v[k]=Math.log1p(p);
            total+=v[k]*v[k];
        }
        double norm=Math.sqrt(Math.max(1e-12,total));
        for(int k=0;k<freq.length;k++)v[k]/=norm;
        v[freq.length]=Math.min(1.0,rms/0.25);
        v[freq.length+1]=Math.min(1.0,abs/0.20);
        v[freq.length+2]=Math.min(1.0,(zc/(double)x.length)/0.20);
        v[freq.length+3]=Math.min(1.0,(x.length/16000.0)/8.0);

        // Normalize entire vector for cosine comparison.
        double n=0;for(double q:v)n+=q*q;n=Math.sqrt(Math.max(1e-12,n));
        for(int i=0;i<v.length;i++)v[i]/=n;
        return new Features(v,x.length/16000.0);
    }

    private static double goertzel(float[] x,int sr,double target){
        int step=Math.max(1,x.length/32000); // bound CPU on long samples
        int n=(x.length+step-1)/step;
        double effectiveSr=sr/(double)step;
        double w=2.0*Math.PI*target/effectiveSr;
        double coeff=2.0*Math.cos(w);
        double s0=0,s1=0,s2=0;
        for(int i=0;i<x.length;i+=step){
            s0=x[i]+coeff*s1-s2;
            s2=s1;s1=s0;
        }
        return (s1*s1+s2*s2-coeff*s1*s2)/Math.max(1,n);
    }

    private static final class Decoded {
        final float[] samples;final int sampleRate;
        Decoded(float[] samples,int sampleRate){this.samples=samples;this.sampleRate=sampleRate;}
    }

    private static Decoded decode(File file)throws Exception{
        MediaExtractor ex=new MediaExtractor();
        MediaCodec codec=null;
        try{
            ex.setDataSource(file.getAbsolutePath());
            int track=-1;
            MediaFormat fmt=null;
            for(int i=0;i<ex.getTrackCount();i++){
                MediaFormat f=ex.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null && mime.startsWith("audio/")){track=i;fmt=f;break;}
            }
            if(track<0 || fmt==null)return null;
            ex.selectTrack(track);
            String mime=fmt.getString(MediaFormat.KEY_MIME);
            int sr=fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)?fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE):44100;
            int channels=fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?Math.max(1,fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)):1;

            codec=MediaCodec.createDecoderByType(mime);
            codec.configure(fmt,null,null,0);
            codec.start();

            ByteArrayOutputStream pcm=new ByteArrayOutputStream();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            boolean inputDone=false, outputDone=false;
            int safety=0;
            while(!outputDone && safety++<20000){
                if(!inputDone){
                    int in=codec.dequeueInputBuffer(10000);
                    if(in>=0){
                        ByteBuffer ib=codec.getInputBuffer(in);
                        if(ib!=null){
                            ib.clear();
                            int n=ex.readSampleData(ib,0);
                            if(n<0){
                                codec.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone=true;
                            }else{
                                codec.queueInputBuffer(in,0,n,ex.getSampleTime(),0);
                                ex.advance();
                            }
                        }
                    }
                }
                int out=codec.dequeueOutputBuffer(info,10000);
                if(out>=0){
                    ByteBuffer ob=codec.getOutputBuffer(out);
                    if(ob!=null && info.size>0){
                        byte[] buf=new byte[info.size];
                        ob.position(info.offset);
                        ob.limit(info.offset+info.size);
                        ob.get(buf);
                        pcm.write(buf);
                    }
                    outputDone=(info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                    codec.releaseOutputBuffer(out,false);
                }else if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat of=codec.getOutputFormat();
                    if(of.containsKey(MediaFormat.KEY_SAMPLE_RATE))sr=of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if(of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))channels=Math.max(1,of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                }
            }

            byte[] raw=pcm.toByteArray();
            if(raw.length<2)return null;
            int frames=raw.length/(2*channels);
            float[] samples=new float[frames];
            for(int i=0;i<frames;i++){
                int sum=0;
                for(int c=0;c<channels;c++){
                    int j=(i*channels+c)*2;
                    int lo=raw[j]&0xff;
                    int hi=raw[j+1];
                    short s=(short)((hi<<8)|lo);
                    sum+=s;
                }
                samples[i]=(sum/(float)channels)/32768f;
            }
            return new Decoded(samples,sr);
        }finally{
            try{ex.release();}catch(Exception ignored){}
            if(codec!=null){
                try{codec.stop();}catch(Exception ignored){}
                try{codec.release();}catch(Exception ignored){}
            }
        }
    }

    private static float[] resample(float[] x,int from,int to){
        if(from<=0 || from==to)return x;
        int n=Math.max(1,(int)Math.round(x.length*(to/(double)from)));
        float[] y=new float[n];
        for(int i=0;i<n;i++){
            double p=i*(from/(double)to);
            int a=(int)p;
            double f=p-a;
            if(a>=x.length-1)y[i]=x[x.length-1];
            else y[i]=(float)(x[a]*(1.0-f)+x[a+1]*f);
        }
        return y;
    }

    private static double cosine(double[] a,double[] b){
        int n=Math.min(a.length,b.length);
        double dot=0,aa=0,bb=0;
        for(int i=0;i<n;i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}
        return dot/Math.sqrt(Math.max(1e-12,aa*bb));
    }

    private static String safe(String s){
        return s==null?"":s.replace('\n',' ').replace('\r',' ').trim();
    }
}
