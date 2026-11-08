package com.susukkang.fgc.clawback.service;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 사용자 시안에 따른 내부 검토용 자체서식: 세로 안내서, 가로 상세 명세, 가로 환수율표. 발행 시 재계산하지 않는다. */
@Component
public class ClawbackNoticePdfRenderer {
    private final byte[] gothic = font("NanumGothic-Regular.ttf");
    private final byte[] bold = font("NanumGothic-Bold.ttf");
    private final byte[] myeongjo = font("NanumMyeongjo-Regular.ttf");
    private static final String CONFIRM_PLACEHOLDER = "확인 후 기재";
    private static final String CONTACT_PLACEHOLDER = "담당자 확인 후 기재";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public record Party(String issuerName, String branchName, String teamName) { }
    public record Notice(String documentNo, int version, OffsetDateTime issuedAt, String contractNo,
                         String insurerName, String productName, String agentCode, String agentName,
                         String triggerLabel, LocalDate eventDate, LocalDate contractDate,
                         String policyLabel, int calculationVersion, OffsetDateTime calculatedAt,
                         Integer elapsedMonths, String rounding, List<Line> lines, BigDecimal total,
                         List<RateBand> rateTable, String reason, Party party) {
        public Notice(String no, int version, OffsetDateTime issuedAt, String contractNo, String insurer,
                      String product, String code, String agent, String trigger, LocalDate event, LocalDate contract,
                      String policy, int calculationVersion, OffsetDateTime calculatedAt, Integer elapsed,
                      String rounding, List<Line> lines, BigDecimal total) {
            this(no,version,issuedAt,contractNo,insurer,product,code,agent,trigger,event,contract,policy,calculationVersion,
                    calculatedAt,elapsed,rounding,lines,total,List.of(),null,null);
        }
        public Notice(String no, int version, OffsetDateTime issuedAt, String contractNo, String insurer,
                      String product, String code, String agent, String trigger, LocalDate event, LocalDate contract,
                      String policy, int calculationVersion, OffsetDateTime calculatedAt, Integer elapsed,
                      String rounding, List<Line> lines, BigDecimal total, List<RateBand> rates, String reason) {
            this(no,version,issuedAt,contractNo,insurer,product,code,agent,trigger,event,contract,policy,calculationVersion,
                    calculatedAt,elapsed,rounding,lines,total,rates,reason,null);
        }
    }
    public record Line(String itemName, Integer installmentNo, BigDecimal basisAmount, BigDecimal appliedRatePct, BigDecimal clawbackAmount) { }
    public record RateBand(Long ruleId, String itemName, Integer monthFrom, Integer monthTo, BigDecimal ratePct, boolean applied, int priority) {
        public RateBand(Long id, String item, Integer from, Integer to, BigDecimal rate, boolean applied) { this(id,item,from,to,rate,applied,0); }
    }
    private record Item(String name, BigDecimal basis, BigDecimal amount) { }

    public byte[] render(Notice n) {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var fonts = new Fonts(PDType0Font.load(pdf,new ByteArrayInputStream(gothic),true),
                    PDType0Font.load(pdf,new ByteArrayInputStream(bold),true), PDType0Font.load(pdf,new ByteArrayInputStream(myeongjo),true));
            pdf.getDocumentInformation().setTitle(n.documentNo()+" · 수수료 환수 안내 및 산출 명세서 v"+n.version());
            pdf.getDocumentInformation().setAuthor("FGC");
            List<Item> items = items(n.lines());
            try (Sheet s = new Sheet(pdf,fonts,false)) { cover(s,n,items); }
            for (int start=0; start<Math.max(1,n.lines().size()); start+=6) {
                var chunk=n.lines().subList(start,Math.min(start+6,n.lines().size()));
                try (Sheet s = new Sheet(pdf,fonts,true)) { details(s,n,chunk,start); }
            }
            for (int start=0; start<Math.max(1,items.size()); start+=4) {
                var chunk=items.subList(start,Math.min(start+4,items.size()));
                try (Sheet s = new Sheet(pdf,fonts,true)) { grounds(s,n,chunk,start); }
            }
            for (int i=0;i<pdf.getNumberOfPages();i++) {
                PDPage page=pdf.getPage(i);
                try (var stream=new PDPageContentStream(pdf,page,PDPageContentStream.AppendMode.APPEND,true)) {
                    float w=page.getMediaBox().getWidth();
                    write(stream,fonts.sans(),8,28,21,"FGC 자체서식 · 내부 검토용 / 실제 통지·확정·회수 완료를 뜻하지 않습니다.");
                    String number=(i+1)+" / "+pdf.getNumberOfPages();
                    write(stream,fonts.sans(),8,w-28-width(fonts.sans(),8,number),21,number);
                }
            }
            pdf.save(out); return out.toByteArray();
        } catch(IOException e) { throw new UncheckedIOException("환수 통지서 PDF를 만들 수 없습니다.",e); }
    }

    private void cover(Sheet s, Notice n, List<Item> all) throws IOException {
        float w=s.w;
        String issuer=n.party()==null?CONFIRM_PLACEHOLDER:known(n.party().issuerName());
        s.text(0,14,w,"[FGC 자체서식 제1호]",s.f.sans(),8,0);
        s.text(w-140,14,140,"수수료 환수 안내용",s.f.sans(),8,2);
        s.cell(new Box(0,34,94,28),"관리번호",0,false,false);
        s.cell(new Box(0,62,94,21),n.documentNo(),1,false,false);
        s.cell(new Box(0,83,94,31),"발행일\n"+date(issued(n)),0,false,false);
        s.cell(new Box(94,34,w-174,80),"",new CellStyle(s.f.bold(),18,1,false,1));
        s.text(94,39,w-174,"수수료 환수 안내 및\n산 출 명 세 서",s.f.bold(),17,1);
        s.text(94,98,w-174,"(설계사 보관용)",s.f.bold(),10,1);
        s.cell(new Box(w-80,34,80,49),"문서 구분\n환수 안내",0,false,false);
        s.cell(new Box(w-80,83,80,31),"계산판 v"+n.calculationVersion(),1,false,false);
        float y=114;
        s.cell(new Box(0,y,40,78),"발\n행\n자",0,false,false);
        s.fields(y,40,new float[]{105,140,100,w-385},new String[]{"(1) 법인명(상호)",issuer,"(2) 담당 부서","수수료 정산팀"});
        s.fields(y+26,40,new float[]{105,140,100,w-385},new String[]{"(3) 사업자등록번호",CONFIRM_PLACEHOLDER,"(4) 담당자",CONTACT_PLACEHOLDER});
        s.fields(y+52,40,new float[]{105,w-145},new String[]{"(5) 소재지(주소)","확인된 사업장 주소 기재"});
        y+=78;
        s.cell(new Box(0,y,40,78),"수\n신\n인",0,false,false);
        s.fields(y,40,new float[]{105,140,100,w-385},new String[]{"(6) 성명",n.agentName(),"(7) 설계사 코드",n.agentCode()});
        s.fields(y+26,40,new float[]{105,140,100,w-385},new String[]{"(8) 소속 지사",n.party()==null?CONFIRM_PLACEHOLDER:known(n.party().branchName()),"(9) 소속 팀",n.party()==null?CONFIRM_PLACEHOLDER:known(n.party().teamName())});
        s.fields(y+52,40,new float[]{105,w-145},new String[]{"(10) 주소","확인된 수신인 주소 기재"});
        y+=78;
        s.cell(new Box(0,y,105,30),"(11) 산정 기준일",0,false,false);
        s.cell(new Box(105,y,165,30),koreanDate(n.eventDate()),1,false,false);
        s.cell(new Box(270,y,100,30),"(12) 대상 계약",0,false,false);
        s.cell(new Box(370,y,w-370,30),"1건 (수수료 "+n.lines().size()+"항목)",1,false,false); y+=30;
        List<Item> summary=new ArrayList<>(all.subList(0,Math.min(5,all.size())));
        if(all.size()>5) summary.add(new Item("그 외 "+(all.size()-5)+"개 항목",all.subList(5,all.size()).stream().map(Item::basis).reduce(BigDecimal.ZERO,BigDecimal::add),all.subList(5,all.size()).stream().map(Item::amount).reduce(BigDecimal.ZERO,BigDecimal::add)));
        float rh=90f/Math.max(3,summary.size()+1);
        float tableH=30+rh*(summary.size()+1);
        s.cell(new Box(0,y,40,tableH),"환\n수\n내\n역",0,false,false);
        float[] cols={135,70,145,w-390};
        float x=40;
        String[] heads={"(13) 수수료 구분","(14) 계약 수","(15) 기지급 기준액\n(원)","(16) 환수 산출액\n(원)"};
        for(int i=0;i<4;i++){s.cell(new Box(x,y,cols[i],30),heads[i],0,true,false);x+=cols[i];} y+=30;
        for(Item item:summary){x=40;String[] values={item.name(),"1건",money(item.basis()),money(item.amount())};for(int i=0;i<4;i++){s.cell(new Box(x,y,cols[i],rh),values[i],new CellStyle(s.f.serif(),10,i>1?2:1,false,.5f));x+=cols[i];}y+=rh;}
        s.cell(new Box(40,y,135,rh),"합 계",new CellStyle(s.f.bold(),10,1,true,.5f));s.cell(new Box(175,y,70,rh),"1건",1,true,false);
        s.cell(new Box(245,y,145,rh),money(basis(n)),new CellStyle(s.f.serif(),11,2,true,.5f));s.cell(new Box(390,y,w-390,rh),money(n.total()),new CellStyle(s.f.bold(),12,2,true,.5f)); y+=rh;
        s.cell(new Box(0,y,140,35),"(17) 환수 안내 금액",new CellStyle(s.f.bold(),10,1,false,1));
        s.cell(new Box(140,y,w-290,35),koreanMoney(n.total()),new CellStyle(s.f.serif(),12,1,false,.5f));
        s.cell(new Box(w-150,y,150,35),"₩ "+money(n.total()),new CellStyle(s.f.bold(),14,2,false,.5f)); y+=35;
        s.cell(new Box(0,y,w,150),"",0,false,false);
        s.text(17,y+20,w-34,"위 계약의 상태 변경에 따라 상품별 환수율을 적용하여 산출한 수수료 환수액을 위와 같이 안내합니다.",s.f.serif(),10,0);
        s.text(17,y+62,w-34,"계약별 지급 내역과 적용 환수율은 별첨 명세서를 확인하시고, 기재 내용에 이견이 있는 경우 관련 자료와 함께 담당 부서로 문의하여 주시기 바랍니다.",s.f.serif(),10,0);
        s.text(0,y+105,w,koreanDate(issued(n)),s.f.serif(),11,1);
        s.text(16,y+130,w-32,"발행인   "+issuer+" 수수료 정산팀    (인)",s.f.serif(),10,2); y+=150;
        s.cell(new Box(0,y,40,60),"납부\n안내",0,false,false);
        s.fields(y,40,new float[]{105,140,100,w-385},new String[]{"(18) 납부 기한",CONTACT_PLACEHOLDER,"(19) 문의 연락처",CONTACT_PLACEHOLDER},30);
        s.fields(y+30,40,new float[]{105,w-145},new String[]{"(20) 입금 계좌","은행·계좌번호·예금주: 승인된 수납 정보 기재"},30);y+=60;
        s.cell(new Box(0,y,40,30),"첨부",0,false,false);
        s.cell(new Box(40,y,w-40,30),"1. 계약별 환수 상세 명세서  2. 상품·회차별 환수율 및 계산 근거",new CellStyle(s.f.serif(),9,0,false,.5f));y+=30;
        s.text(0,y+12,w,"작 성 방 법",s.f.bold(),10,0);
        s.text(0,y+33,w,"1. 계약 수는 중복을 제외한 건수이며, 항목별 기지급 기준액과 환수액을 합산합니다.\n2. 상품별 적용 정책·회차·환수율과 계산판을 확인한 후 발행합니다.\n3. 본 문서는 FGC 자체서식이며 법정서식이 아닙니다.",s.f.sans(),8,0);
    }

    private void landscapeHeader(Sheet s,Notice n,String title,int appendix) throws IOException {
        s.text(0,14,s.w,"[FGC 자체서식 제1호 부표 "+appendix+"]",s.f.sans(),8,0);
        s.text(s.w-250,14,250,"문서번호 "+n.documentNo(),s.f.sans(),8,2);
        s.cell(new Box(0,34,100,60),"귀속연도\n"+(n.eventDate()==null?"확인 필요":n.eventDate().getYear()+"년"),0,false,false);
        s.cell(new Box(100,34,s.w-200,60),title,new CellStyle(s.f.bold(),20,1,false,1));
        s.cell(new Box(s.w-100,34,100,60),"설계사 보관용\n계산판 v"+n.calculationVersion(),0,false,false);
        s.fields(94,0,new float[]{70,120,100,165,100,s.w-555},new String[]{"(1) 성명",n.agentName(),"(2) 설계사 코드",n.agentCode(),"(3) 산정 기준일",date(n.eventDate())},28);
    }
    private void details(Sheet s,Notice n,List<Line> lines,int start) throws IOException {
        landscapeHeader(s,n,"계약별 환수 상세 명세서",1);
        float[] cols={27,82,128,54,74,60,96,96,49,s.w-666};
        s.cell(new Box(0,122,425,24),"계약 및 환수 원인",new CellStyle(s.f.bold(),9,1,true,.5f));
        s.cell(new Box(425,122,s.w-425,24),"환수 산출 내역 (단위: 원)",new CellStyle(s.f.bold(),9,1,true,.5f));
        String[] heads={"번호","(4) 보험회사","(5) 계약번호 / 상품","(6) 계약일","(7) 원인\n발생일","(8) 사유\n적용 회차","(9) 수수료 항목","(10) 기지급 기준액","(11)\n환수율","(12) 환수 금액"};
        float x=0;for(int i=0;i<cols.length;i++){s.cell(new Box(x,146,cols[i],30),heads[i],0,true,false);x+=cols[i];}
        float h=Math.max(1,lines.size())*40f;
        String[] contract={"1",n.insurerName(),n.contractNo()+"\n"+n.productName(),date(n.contractDate()),date(n.eventDate()),n.triggerLabel()+"\n"+period(n)};
        x=0;for(int i=0;i<6;i++){s.cell(new Box(x,176,cols[i],h),contract[i],new CellStyle(s.f.serif(),9,1,false,.5f));x+=cols[i];}
        float y=176;for(Line line:lines){x=425;String[] values={line.itemName()+"\n(지급 "+text(line.installmentNo())+"회차)",money(line.basisAmount()),rate(line.appliedRatePct()),money(line.clawbackAmount())};for(int i=6;i<10;i++){s.cell(new Box(x,y,cols[i],40),values[i-6],new CellStyle(s.f.serif(),i==7||i==9?11f:9f,i==7||i==9?2:1,false,.5f));x+=cols[i];}y+=40;}
        s.cell(new Box(0,176+h,s.w,276-h),"이 하 여 백",new CellStyle(s.f.serif(),9,1,false,.5f));
        s.cell(new Box(0,452,521,30),start==0&&n.lines().size()<=6?"합 계":"이 쪽 합계",new CellStyle(s.f.bold(),10,1,true,1));
        s.cell(new Box(521,452,96,30),money(lines.stream().map(Line::basisAmount).reduce(BigDecimal.ZERO,BigDecimal::add)),new CellStyle(s.f.serif(),11,2,true,.5f));
        s.cell(new Box(617,452,49,30),"-",1,true,false);
        s.cell(new Box(666,452,s.w-666,30),money(lines.stream().map(Line::clawbackAmount).reduce(BigDecimal.ZERO,BigDecimal::add)),new CellStyle(s.f.bold(),12,2,true,.5f));
        s.text(0,496,s.w,"※ 동일 계약의 수수료 항목별 환수율은 적용 정책에 따라 다를 수 있습니다.\n※ 적용 회차와 환수율의 근거는 부표 2의 상품별 환수율표에서 확인합니다.\n※ 전체 기지급 기준액: "+money(basis(n))+"원 / 환수 산출액: "+money(n.total())+"원 / "+known(n.rounding()),s.f.sans(),8,0);
    }
    private void grounds(Sheet s,Notice n,List<Item> items,int start) throws IOException {
        landscapeHeader(s,n,"상품·회차별 환수율 및 계산 근거",2);
        float product=158;
        float itemW=77;
        float rateW=(s.w-product-itemW)/13;
        s.cell(new Box(0,122,product,46),"(4) 상품 및 적용 정책",new CellStyle(s.f.bold(),9,1,true,.5f));
        s.cell(new Box(product,122,itemW,46),"(5) 수수료\n항목",0,true,false);
        s.cell(new Box(product+itemW,122,s.w-product-itemW,22),"(6) 환수 기준 회차별 환수율 (%)",new CellStyle(s.f.bold(),9,1,true,.5f));
        for(int month=0;month<13;month++)s.cell(new Box(product+itemW+month*rateW,144,rateW,24),month==12?"13회\n이상":(month+1)+"회",0,true,false);
        float rowH=26;
        float y=168;
        s.cell(new Box(0,y,product,Math.max(2,items.size())*rowH),n.productName()+"\n"+n.policyLabel(),new CellStyle(s.f.serif(),9,1,false,.5f));
        y = rateRows(s, n, items, y, product, itemW, rateW);
        s.cell(new Box(0,y,80,29),"적용 기준",0,false,false);
        s.cell(new Box(80,y,s.w-80,29),period(n)+" · "+n.triggerLabel()+" / 음영 및 굵은 테두리 셀이 이번 계산에 적용된 환수율입니다.",new CellStyle(s.f.sans(),8,0,false,.5f));y+=41;
        s.cell(new Box(0,y,s.w,24),"(7) 적용 환수율에 따른 계약별 산출 내역",new CellStyle(s.f.bold(),9,1,true,1));y+=24;
        float[] cols={172,64,210,210,s.w-656};
        String first=items.isEmpty()?"수수료":items.getFirst().name();
        String second=items.size()>1?items.get(1).name():"기타 수수료";
        String[] heads={"계약번호 / 상품","적용 회차",first+" 산식",second+" 산식","환수액 (원)"};
        float x=0;for(int i=0;i<5;i++){s.cell(new Box(x,y,cols[i],26),heads[i],0,true,false);x+=cols[i];}y+=26;
        y = formulaRows(s, n, items, y, cols);
        s.cell(new Box(0,y,s.w-cols[4],26),start==0&&items.size()==items(n.lines()).size()?"합 계":"이 쪽 합계",new CellStyle(s.f.bold(),10,1,true,1));
        s.cell(new Box(s.w-cols[4],y,cols[4],26),money(items.stream().map(Item::amount).reduce(BigDecimal.ZERO,BigDecimal::add)),new CellStyle(s.f.bold(),12,2,true,1));y+=38;
        s.text(0,y,s.w,"※ 환수 기준 회차는 경과월 + 1이며 지급회차와 구분합니다. 13회 이상에 여러 구간이 있으면 ‘구간별’로 표시합니다.\n※ - 는 저장된 정책 구간이 없다는 뜻입니다. 과거 계산판에 환수율표가 없으면 실제 적용한 환수율만 표시합니다.\n※ "+known(n.rounding())+" / 계산일시: "+(n.calculatedAt()==null?"-":n.calculatedAt().atZoneSameInstant(SEOUL).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))),s.f.sans(),7.5f,0);
    }

    private static BigDecimal itemAmount(Item item) { return item == null ? BigDecimal.ZERO : item.amount(); }

    private float formulaRows(Sheet s, Notice n, List<Item> items, float y, float[] cols) throws IOException {
        for(int pair=0;pair<Math.max(1,items.size());pair+=2){
            Item a=items.isEmpty()?null:items.get(pair);
            Item b=pair+1<items.size()?items.get(pair+1):null;
            String[] cells={n.contractNo()+"\n"+n.productName(),period(n),formula(n,a),formula(n,b),money(itemAmount(a).add(itemAmount(b)))};
            float x=0;for(int i=0;i<5;i++){s.cell(new Box(x,y,cols[i],38),cells[i],new CellStyle(s.f.serif(),i==4?11f:9f,i==4?2:1,false,.5f));x+=cols[i];}y+=38;
        }
        return y;
    }

    private float rateRows(Sheet s, Notice n, List<Item> items, float y, float product, float itemW, float rateW) throws IOException {
        float rowH = 26;
        for(Item item:items){
            s.cell(new Box(product,y,itemW,rowH),item.name(),1,false,false);
            for(int month=0;month<13;month++){
                boolean applied=appliesAt(n, month);
                String value=matrixValue(n,item.name(),month);
                s.cell(new Box(product+itemW+month*rateW,y,rateW,rowH),value,new CellStyle(applied?s.f.bold():s.f.serif(),10,1,applied,applied?1.4f:.5f));
            }y+=rowH;
        }
        if(items.size()<2){s.cell(new Box(product,y,s.w-product,rowH),"",0,false,false);y+=rowH;}
        return y;
    }

    private static boolean appliesAt(Notice notice, int month) {
        if (notice.elapsedMonths() == null) return false;
        return month == 12 ? notice.elapsedMonths() >= 12 : notice.elapsedMonths() == month;
    }

    private String matrixValue(Notice n,String item,int month){
        List<RateBand> rates=n.rateTable()==null?List.of():n.rateTable();
        var matching=rates.stream().filter(r->r.itemName().equals(item)||r.itemName().equals("전체 수수료 항목"))
                .filter(r->r.monthFrom()!=null&&r.monthTo()!=null&&(month==12||r.monthFrom()<=month)&&r.monthTo()>=month)
                .sorted(Comparator.comparingInt(RateBand::priority).reversed()).toList();
        if(month==12 && matching.stream().map(RateBand::ratePct).map(BigDecimal::stripTrailingZeros).distinct().count()>1)return "구간별";
        if(!matching.isEmpty()){
            var best=matching.getFirst();
            if(matching.size()>1&&matching.get(1).priority()==best.priority()&&matching.get(1).ratePct().compareTo(best.ratePct())!=0)return "확인";
            return best.ratePct().stripTrailingZeros().toPlainString();
        }
        boolean applied=appliesAt(n, month);
        if(applied){var actual=n.lines().stream().filter(l->l.itemName().equals(item)).map(Line::appliedRatePct).distinct().toList();if(actual.size()==1)return actual.getFirst().stripTrailingZeros().toPlainString();}
        return "-";
    }
    private static String formula(Notice n,Item item){
        if(item==null)return "-";
        var lines=n.lines().stream().filter(l->l.itemName().equals(item.name())).toList();
        var rates=lines.stream().map(Line::appliedRatePct).map(BigDecimal::stripTrailingZeros).distinct().toList();
        return rates.size()==1&&item.basis().multiply(rates.getFirst()).divide(BigDecimal.valueOf(100),0,RoundingMode.HALF_UP).compareTo(item.amount())==0
                ?money(item.basis())+" × "+rate(rates.getFirst())+" = "+money(item.amount()):"지급 "+lines.size()+"행의 개별 산출액 합계 = "+money(item.amount());
    }
    private static List<Item> items(List<Line> lines){
        Map<String,Item> items=new LinkedHashMap<>();
        for(Line line:lines)items.compute(line.itemName(),(key,previous)->new Item(key,(previous==null?BigDecimal.ZERO:previous.basis()).add(line.basisAmount()),(previous==null?BigDecimal.ZERO:previous.amount()).add(line.clawbackAmount())));
        return List.copyOf(items.values());
    }
    private static BigDecimal basis(Notice n){return n.lines().stream().map(Line::basisAmount).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static String period(Notice n){return n.elapsedMonths()==null?"회차 확인 필요":(n.elapsedMonths()+1)+"회차";}
    private static LocalDate issued(Notice n){return n.issuedAt()==null?null:n.issuedAt().atZoneSameInstant(SEOUL).toLocalDate();}
    private static String date(LocalDate d){return d==null?"-":d.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));}
    private static String koreanDate(LocalDate d){return d==null?"-":d.format(DateTimeFormatter.ofPattern("yyyy년   M월   d일"));}
    private static String known(String s){return s==null||s.isBlank()?CONFIRM_PLACEHOLDER:s;}
    private static String text(Object o){return o==null?"-":o.toString();}
    private static String money(BigDecimal v){var f=new DecimalFormat("#,##0");f.setRoundingMode(RoundingMode.UNNECESSARY);return f.format(v);}
    private static String rate(BigDecimal v){return v==null?"-":v.stripTrailingZeros().toPlainString()+"%";}
    static String koreanMoney(BigDecimal amount){
        String number=amount.toBigIntegerExact().toString();
        if(number.startsWith("-"))return "금 마이너스 "+koreanMoney(amount.negate()).substring(2);
        if(amount.signum()==0)return "금 영원정";
        String[] large={"","만","억","조","경"};
        StringBuilder result=new StringBuilder();
        int group=0;
        for(int end=number.length();end>0;end-=4,group++){
            String segment = koreanGroup(number.substring(Math.max(0,end-4),end));
            if(!segment.isEmpty()) result.insert(0,segment+(group<large.length?large[group]:""));
        }
        return "금 "+result+"원정";
    }
    private static String koreanGroup(String part) {
        String[] digits={"","일","이","삼","사","오","육","칠","팔","구"};
        String[] units={"","십","백","천"};
        StringBuilder segment=new StringBuilder();
        for(int i=0;i<part.length();i++){
            int digit=part.charAt(i)-'0';
            int position=part.length()-i-1;
            if(digit>0) segment.append(digit==1&&position>0?"":digits[digit]).append(units[position]);
        }
        return segment.toString();
    }
    private static byte[] font(String name){try(var in=new ClassPathResource("fonts/"+name).getInputStream()){return in.readAllBytes();}catch(IOException e){throw new UncheckedIOException(e);}}
    private record Fonts(PDType0Font sans,PDType0Font bold,PDType0Font serif) { }
    private static float width(PDType0Font f,float size,String text)throws IOException{return f.getStringWidth(text)*size/1000;}
    private static void write(PDPageContentStream stream,PDType0Font font,float size,float x,float y,String value)throws IOException{
        stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(x,y);stream.showText(value);stream.endText();
    }
    private record Box(float x, float top, float width, float height) { }
    private record CellStyle(PDType0Font font, float size, int align, boolean gray, float border) { }
    private static final class Sheet implements AutoCloseable {
        private static float alignedOffset(int align, float space, float padding) {
            if (align == 2) return space - padding;
            if (align == 1) return space / 2;
            return padding;
        }

        final Fonts f;
        final float w;
        final float h;
        final PDPageContentStream stream;
        Sheet(PDDocument pdf,Fonts fonts,boolean landscape)throws IOException{
            f=fonts;var rectangle=landscape?new PDRectangle(PDRectangle.A4.getHeight(),PDRectangle.A4.getWidth()):PDRectangle.A4;
            var page=new PDPage(rectangle);pdf.addPage(page);h=rectangle.getHeight();w=rectangle.getWidth()-56;stream=new PDPageContentStream(pdf,page);
        }
        void fields(float top,float x,float[] widths,String[] values)throws IOException{fields(top,x,widths,values,26);}
        void fields(float top,float x,float[] widths,String[] values,float height)throws IOException{
            for(int i=0;i<values.length;i++){cell(new Box(x,top,widths[i],height),values[i],new CellStyle(i%2==0?f.sans():f.serif(),9,i%2==0?1:0,false,.5f));x+=widths[i];}
        }
        void cell(Box box,String value,int style,boolean gray,boolean heavy)throws IOException{cell(box,value,new CellStyle(style==1?f.serif():f.sans(),9,1,gray,heavy?1.3f:.5f));}
        void cell(Box box,String value,CellStyle style)throws IOException{
            float x=box.x();
            float top=box.top();
            float width=box.width();
            float height=box.height();
            PDType0Font font=style.font();
            float size=style.size();
            int align=style.align();
            boolean gray=style.gray();
            float border=style.border();
            if(gray){stream.setNonStrokingColor(.91f);stream.addRect(28+x,h-top-height,width,height);stream.fill();}
            stream.setNonStrokingColor(0f);stream.setStrokingColor(0f);stream.setLineWidth(border);stream.addRect(28+x,h-top-height,width,height);stream.stroke();
            List<String> lines=wrap(value,font,size,width-10);
            while(lines.size()*size*1.35f>height-4&&size>5.8f){size-=.25f;lines=wrap(value,font,size,width-10);}
            float y=top+(height-lines.size()*size*1.35f)/2+size*.95f;
            for(String line:lines){float offset=alignedOffset(align,width-width(font,size,line),5);write(stream,font,size,28+x+offset,h-y,line);y+=size*1.35f;}
        }
        void text(float x,float top,float width,String value,PDType0Font font,float size,int align)throws IOException{
            for(String line:wrap(value,font,size,width)){float offset=alignedOffset(align,width-width(font,size,line),0);write(stream,font,size,28+x+offset,h-top-size,line);top+=size*1.65f;}
        }
        private List<String> wrap(String value,PDType0Font font,float size,float width)throws IOException{
            List<String> lines=new ArrayList<>();
            StringBuilder current=new StringBuilder();
            for(int point:ClawbackNoticePdfRenderer.text(value).codePoints().toArray()){
                if(point=='\n'){lines.add(current.toString());current.setLength(0);continue;}
                String next=Character.toString(point);try{font.encode(next);}catch(IOException|IllegalArgumentException unsupported){next="?";}
                if(!current.isEmpty()&&width(font,size,current+next)>width){lines.add(current.toString());current.setLength(0);}current.append(next);
            }lines.add(current.toString());return lines;
        }
        public void close()throws IOException{stream.close();}
    }
}
