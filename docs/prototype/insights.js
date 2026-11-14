/* Scenario-only calendar and reports. Uses the temporary state of index.html. */
function ixState(){
 if(!state.insights)state.insights={month:state.date.slice(0,7),period:7,end:state.date};
 return state.insights;
}
function ixDateValid(date){return calendarDay(date)!==null&&date<=TODAY;}
function ixDates(end,count){
 const last=calendarDay(end),first=calendarDay(MIN_DATE);
 if(last===null)return [];
 return Array.from({length:Math.min(count,last-first+1)},(_,i)=>dateFromDay(last-Math.min(count,last-first+1)+1+i));
}
function ixWeight(d){return Number.isSafeInteger(d?.weightCents)&&d.weightCents>0?d.weightCents:null;}
function ixSleep(d){return Number.isSafeInteger(d?.sleepMinutes)&&d.sleepMinutes>=0?d.sleepMinutes:null;}
function ixFoodStatus(d){return d?.complete===true?'complete':d?.entries?.length?'partial':'missing';}
function ixHasRecord(d){return ixFoodStatus(d)!=='missing'||ixWeight(d)!==null||ixSleep(d)!==null||ixHasMoment(d);}
function ixHasMoment(d){return Boolean(d?.mood||d?.note?.trim()||d?.photos?.length);}
function ixMomentsReport(rows){
 const moments=rows.filter(r=>ixHasMoment(r.record)),photos=moments.reduce((n,r)=>n+(r.record.photos?.length||0),0),notes=moments.filter(r=>r.record.note?.trim()).length;
 return `<section class="card stack"><span class="eyebrow">За кадром цифр</span><h3>Маленькие моменты</h3><div class="report-stats"><div><strong class="report-value">${photos}</strong><p class="small muted">фотографий</p></div><div><strong class="report-value">${notes}</strong><p class="small muted">дней с заметками</p></div></div><p class="small muted">Настроение отмечено за ${moments.filter(r=>r.record.mood).length} дн. Личные моменты не меняют полноту питания и уровень дневника.</p>${moments.length?moments.slice(-3).reverse().map(r=>`<button class="choice" data-action="insight-open-day" data-date="${r.date}"><span class="grow"><strong>${dateLabel(r.date)}</strong><span class="small muted" style="display:block">${esc((r.record.note||'').slice(0,100)||'Открыть моменты дня')}</span></span><span aria-hidden="true">›</span></button>`).join(''):'<p class="small muted">Добавьте фото, настроение или пару слов в «Мой день».</p>'}</section>`;
}
function ixAggregate(dates){
 const rows=dates.map(date=>({date,record:state.days[date]}));
 const complete=rows.filter(r=>ixFoodStatus(r.record)==='complete');
 const partial=rows.filter(r=>ixFoodStatus(r.record)==='partial');
 const weights=rows.map(r=>ixWeight(r.record)).filter(v=>v!==null),sleep=rows.map(r=>ixSleep(r.record)).filter(v=>v!==null);
 const food=Array.from({length:4},(_,i)=>decimalMean(complete.map(r=>decimalSum((r.record.entries||[]).map(e=>decimalFromNumber(e.values[i]))))));
 const top=new Map();
 rows.forEach(r=>(r.record?.entries||[]).forEach(e=>{
  const key=String(e.name),item=top.get(key)||{name:key,count:0,dates:new Set()};item.count++;item.dates.add(r.date);top.set(key,item);
 }));
 return {rows,complete:complete.length,partial:partial.length,missing:dates.length-complete.length-partial.length,active:rows.filter(r=>ixHasRecord(r.record)).length,weight:{mean:integerMean(weights),count:weights.length},sleep:{mean:integerMean(sleep),count:sleep.length},food,top:[...top.values()].sort((a,b)=>b.count-a.count||a.name.localeCompare(b.name,'ru')).slice(0,5)};
}
function ixMonthShift(month,amount){
 const [year,number]=month.split('-').map(Number),absolute=(year-1)*12+number-1+amount;
 if(absolute<0||absolute>119987)return null;
 return String(Math.floor(absolute/12)+1).padStart(4,'0')+'-'+String(absolute%12+1).padStart(2,'0');
}
function ixMonthDates(month){
 const first=calendarDay(month+'-01');if(first===null)return [];
 const next=ixMonthShift(month,1),last=next?calendarDay(next+'-01')-1:calendarDay('9999-12-31');
 return Array.from({length:last-first+1},(_,i)=>dateFromDay(first+i));
}
function ixDayLabel(date){
 const d=state.days[date],food=ixFoodStatus(d),parts=[dateLabel(date),food==='complete'?'питание: день заполнен':food==='partial'?'питание: частично':'питание: нет записей'];
 if(ixWeight(d)!==null)parts.push('есть вес');if(ixSleep(d)!==null)parts.push('есть сон');if(ixHasMoment(d))parts.push('есть личные моменты');
 return parts.join(' · ');
}
function calendarView(){
 const ui=ixState(),month=ui.month,dates=ixMonthDates(month),prev=ixMonthShift(month,-1),next=ixMonthShift(month,1);
 const weekday=(new Date(month+'-01T00:00:00Z').getUTCDay()+6)%7;
 const label=new Intl.DateTimeFormat('ru-RU',{month:'long',year:'numeric',timeZone:'UTC'}).format(new Date(month+'-01T00:00:00Z'));
 const validDates=dates.filter(d=>d<=TODAY),data=ixAggregate(validDates);
 return `<div class="screen calendar-screen">${header('home','Календарь','У каждого дня своя история. Выберите дату, чтобы открыть дневник.')}<section class="card stack"><div class="row"><button class="icon-button" data-action="calendar-prev" aria-label="Предыдущий месяц" ${prev?'':'disabled'}>‹</button><h2 class="calendar-month">${label}</h2><button class="icon-button" data-action="calendar-next" aria-label="Следующий месяц" ${next&&next<=TODAY.slice(0,7)?'':'disabled'}>›</button></div><div class="calendar-grid" aria-label="Дни месяца">${['Пн','Вт','Ср','Чт','Пт','Сб','Вс'].map(d=>`<span class="calendar-weekday" aria-hidden="true">${d}</span>`).join('')}${'<span aria-hidden="true"></span>'.repeat(weekday)}${dates.map(date=>{
  const d=state.days[date],status=ixFoodStatus(d),future=date>TODAY;
  return `<button class="calendar-day ${date===state.date?'is-selected':''} ${date===TODAY?'is-today':''} is-${status==='missing'?'empty':status}" data-action="insight-open-day" data-date="${date}" aria-label="${esc(ixDayLabel(date))}${future?' · будущая дата':''}" ${date===state.date?'aria-pressed="true"':'aria-pressed="false"'} ${future?'disabled':''}><strong>${Number(date.slice(8))}</strong><span class="calendar-dots" aria-hidden="true">${status!=='missing'?`<i class="food-dot ${status==='complete'?'filled':'partial'}"></i>`:''}${ixWeight(d)!==null?'<i class="weight-dot"></i>':''}${ixSleep(d)!==null?'<i class="sleep-dot"></i>':''}${ixHasMoment(d)?'<i class="journal-dot"></i>':''}</span></button>`;
 }).join('')}</div><div class="calendar-legend"><span>● Питание</span><span>◆ Вес</span><span>☾ Сон</span><span>✦ Моменты</span><span>Кольцо — частичное питание</span></div><button class="link" data-action="calendar-current">К текущему месяцу</button></section><section class="card calendar-summary stack"><span class="eyebrow">Этот месяц · до ${validDates.at(-1)?dateLabel(validDates.at(-1)):'начала периода'}</span><div class="report-stats"><div><strong class="report-value">${data.active}</strong><p class="small muted">дней с записями</p></div><div><strong class="report-value">${data.complete}</strong><p class="small muted">дней с заполненным питанием</p></div></div><p class="small muted">Вес: ${data.weight.count} дн. · Сон: ${data.sleep.count} дн. Отсутствие точки означает отсутствие записи, а не нулевое значение.</p></section><button class="button secondary" data-action="report">Посмотреть отчёт</button></div>`;
}
function ixSigned(value,format){return value===0?'Без изменения':(value>0?'+':'−')+format(Math.abs(value));}
function ixDifference(now,previous,format){
 if(now===null||previous===null)return 'Для сравнения нужны записи в обоих периодах';
 const delta=now-previous;return Number.isFinite(delta)?ixSigned(delta,format)+' к предыдущему периоду':'Разница слишком велика для расчёта';
}
function ixMiniChart(rows,key,label){
 const values=rows.map(r=>key==='food'?(ixFoodStatus(r.record)==='complete'?decimalToNumber(decimalSum((r.record?.entries||[]).map(e=>decimalFromNumber(e.values[0])))):null):key==='weight'?ixWeight(r.record):ixSleep(r.record));
 const usable=values.filter(v=>v!==null&&Number.isFinite(v));
 if(!usable.length)return '<p class="small muted">График появится после записей</p>';
 const min=key==='weight'?Math.min(...usable):0,max=Math.max(...usable),padding=max===min?Math.max(1,max*.01):0,low=Math.max(0,min-padding),high=max+padding;
 const x=i=>10+i/Math.max(1,rows.length-1)*280,y=v=>12+(high-v)/(high-low||1)*64;
 let paths='';for(let i=1;i<values.length;i++)if(values[i-1]!==null&&Number.isFinite(values[i-1])&&values[i]!==null&&Number.isFinite(values[i]))paths+=`<path class="chart-line" d="M${x(i-1)},${y(values[i-1])}L${x(i)},${y(values[i])}"/>`;
 const dots=values.map((v,i)=>v!==null&&Number.isFinite(v)?`<circle class="chart-point" cx="${x(i)}" cy="${y(v)}" r="3"><title>${esc(dateLabel(rows[i].date))} · ${esc(key==='weight'?weightText(v)+' кг':key==='sleep'?sleepText(v):fmt(v)+' ккал')}</title></circle>`:'').join('');
 const scale=key==='weight'?`${weightText(low)}–${weightText(high)} кг`:key==='sleep'?`0–${sleepText(high)}`:`0–${fmt(high)} ккал`;
 return `<div class="report-spark progress-chart"><svg viewBox="0 0 300 90" role="img" aria-label="${esc(label)}. Пропуски разрывают линию. ${esc(scale)}."><path class="chart-grid" d="M10 76H290"/>${paths}${dots}</svg><p class="small muted">${esc(scale)} · пропуски не соединены</p></div>`;
}
function ixReportRange(){const ui=ixState();return ixDateValid(ui.end)?ixDates(ui.end,ui.period):null;}
function ixCoverage(label,count,days){return `<div class="report-coverage"><div class="row"><strong>${label}</strong><span>${count} / ${days} дн.</span></div><div class="track"><div class="fill" style="width:${days?count/days*100:0}%"></div></div></div>`;}
function reportView(){
 const ui=ixState(),dates=ixReportRange(),controls=`<div class="progress-tabs" role="group" aria-label="Период отчёта">${[7,30,90].map(period=>`<button class="button ${period===ui.period?'':'secondary'}" data-action="report-period" data-period="${period}" aria-pressed="${period===ui.period}">${period} дней</button>`).join('')}</div>${field('report-end','Последний день периода',ui.end,`type="date" min="${MIN_DATE}" max="${TODAY}" aria-describedby="report-error"`)}`;
 if(!dates)return `<div class="screen report-screen">${header('home','Мой отчёт')}${controls}<p class="error" id="report-error" role="alert">Выберите дату не позднее ${dateLabel(TODAY)}.</p></div>`;
 const start=calendarDay(dates[0]),min=calendarDay(MIN_DATE),previousDates=start>min?ixDates(dateFromDay(start-1),ui.period):[],data=ixAggregate(dates),previous=ixAggregate(previousDates),food=data.food[0];
 const periodLabel=dateLabel(dates[0])+' — '+dateLabel(dates.at(-1)),previousLabel=previousDates.length?dateLabel(previousDates[0])+' — '+dateLabel(previousDates.at(-1)):'Предыдущий период недоступен';
 const foodDiff=ixDifference(food.mean,previous.food[0].mean,n=>fmt(n)+' ккал'),weightDiff=ixDifference(data.weight.mean,previous.weight.mean,n=>weightText(n)+' кг'),sleepDiff=ixDifference(data.sleep.mean,previous.sleep.mean,sleepText);
 return `<div class="screen report-screen">${header('home','Мой отчёт','Меньше догадок. Больше понимания своего ритма.')}<section class="report-hero"><span class="eyebrow">Обзор привычек</span><h2>${data.active?data.active+' дней вашей истории':'Начало вашей истории'}</h2><p class="small">${periodLabel}</p><p class="small muted">Записи помогают замечать изменения. Здесь нет оценки тела или «правильности» питания.</p></section>${controls}<p class="error" id="report-error" role="alert"></p>${data.active?'':'<section class="card stack"><h3>Пока чистый лист</h3><p class="small muted">Добавьте питание, вес или сон. Отчёт заполнится вашими записями без выдуманных нулей.</p><button class="button secondary" data-action="home">Открыть дневник</button></section>'}<section class="card stack"><h3>Насколько полная картина</h3>${ixCoverage('Питание · заполненные дни',data.complete,dates.length)}${ixCoverage('Вес · дни с измерением',data.weight.count,dates.length)}${ixCoverage('Сон · дни с записью',data.sleep.count,dates.length)}<p class="small muted">Питание: частично ${data.partial}, без записей ${data.missing}. Каждый показатель использует свою выборку.</p></section><div class="report-stats"><section class="card report-stat"><span class="eyebrow">Средний вес</span><p class="report-value" id="report-weight-mean">${data.weight.mean===null?'—':weightText(data.weight.mean)+' кг'}</p><p class="small muted">N = ${data.weight.count} / ${dates.length} дн.</p><p class="small">${weightDiff}</p></section><section class="card report-stat"><span class="eyebrow">Средний сон</span><p class="report-value" id="report-sleep-mean">${data.sleep.mean===null?'—':sleepText(data.sleep.mean)}</p><p class="small muted">N = ${data.sleep.count} / ${dates.length} дн.</p><p class="small">${sleepDiff}</p></section></div><section class="card stack"><div class="row"><h3>Питание за период</h3><span class="pill">N = ${data.complete} / ${dates.length}</span></div><p class="report-value" id="report-food-mean">${food.text}${food.mean===null?'':' ккал / день'}</p><p class="small muted">Среднее только по заполненным дням, включая явно подтверждённый нулевой день.</p><div class="report-stats">${['Белки','Жиры','Углеводы'].map((label,i)=>`<div><p class="small muted">${label}</p><strong>${data.food[i+1].text}${data.food[i+1].mean===null?'':' г'}</strong></div>`).join('')}</div><p class="small">${foodDiff}</p>${ixMiniChart(data.rows,'food','Питание по заполненным дням')}</section><section class="card stack"><h3>Как менялись записи веса</h3>${ixMiniChart(data.rows,'weight','Измерения веса')}<button class="link" data-action="report-chart" data-metric="weight">Точные значения и даты →</button></section><section class="card stack"><h3>Ритм сна</h3>${ixMiniChart(data.rows,'sleep','Записи сна')}<button class="link" data-action="report-chart" data-metric="sleep">Открыть подробный график →</button></section><section class="card stack"><h3>Календарь заботы о себе</h3><p class="small muted">Каждая ячейка — день. Акцентная ячейка — питание заполнено; приглушённая — есть другие записи; пустая — записей нет. Цвет не оценивает результат.</p><div class="report-heatmap" aria-label="Записи за период">${data.rows.map(r=>`<button class="heat-day ${ixHasRecord(r.record)?'has-record':''} ${ixFoodStatus(r.record)==='complete'?'food-complete':''}" data-action="insight-open-day" data-date="${r.date}" aria-label="${esc(ixDayLabel(r.date))}">${Number(r.date.slice(8))}</button>`).join('')}</div><button class="link" data-action="calendar">Открыть календарь →</button></section><section class="card stack"><h3>Часто в дневнике</h3><p class="small muted">Частота записей, без рейтинга пользы. Учтены все внесённые порции периода, в том числе частичные дни.</p>${data.top.length?data.top.map((item,i)=>`<div class="row report-top"><span class="pill">${i+1}</span><strong class="grow">${esc(item.name)}</strong><span class="small">${item.count} зап.<br>${item.dates.size} дн.</span></div>`).join(''):'<p class="small muted">Добавьте еду, чтобы увидеть частые записи.</p>'}</section>${ixMomentsReport(data.rows)}<details class="progress-details"><summary>Как посчитан отчёт</summary><div class="stack"><p class="small muted">Основной период: ${periodLabel}. Предыдущий: ${previousLabel}. Сравниваются средние каждого показателя по его собственной выборке, а не одинаковые даты.</p><p class="small muted">В предыдущем периоде: вес N=${previous.weight.count}, сон N=${previous.sleep.count}, заполненное питание N=${previous.complete}. Значения веса и сна округляются до 0,01 кг и минуты соответственно; питание — до 0,1. Частичные дни не входят в среднее питания. Отсутствующая запись никогда не заменяется нулём.</p><p class="small muted">Короткие графики показывают фактические записи. Исторические цели и точная таблица доступны в подробном графике питания.</p><button class="link" data-action="report-chart" data-metric="food">Подробное питание и цели →</button></div></details><p class="footer-note">Данные макета временные. Отчёт описывает записи и не даёт медицинских заключений.</p></div>`;
}
function insightsInput(id,value){
 if(id!=='report-end')return false;ixState().end=value;render();return true;
}
function insightsAction(action,button){
 if(action==='calendar'){ixState().month=state.date.slice(0,7);go('calendar');return true;}
 if(action==='report-back'){go('report');return true;}
 if(action==='report'){ixState().end=state.date;go('report');return true;}
 if(action==='calendar-prev'||action==='calendar-next'){
  const ui=ixState(),month=ixMonthShift(ui.month,action==='calendar-prev'?-1:1);if(month&&month<=TODAY.slice(0,7)){ui.month=month;render();}return true;
 }
 if(action==='calendar-current'){ixState().month=TODAY.slice(0,7);render();return true;}
 if(action==='report-period'){const period=Number(button.dataset.period);if([7,30,90].includes(period)){ixState().period=period;render();}return true;}
 if(action==='insight-open-day'){const date=button.dataset.date;if(ixDateValid(date)){state.date=date;ensureDay(date);go('home');}return true;}
 if(action==='report-chart'){
  const metric=button.dataset.metric;if(!['weight','sleep','food'].includes(metric)||!ixReportRange())return true;
  const ui=ixState(),dates=ixReportRange();state.progress={metric,nutrient:0,completeOnly:false,preset:'custom',end:ui.end,from:dates[0],selectedDate:ui.end};go('progress');return true;
 }
 return false;
}
