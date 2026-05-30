import { useState } from "react";
import Calendar from "react-calendar";
import "react-calendar/dist/Calendar.css"; 

export default function MiniCalendar() {
  // 오늘 날짜를 기준으로 초기 문자열 생성
  const getTodayDisplayString = () => {
    const date = new Date();
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    return `${year}-${month}-${day}`;
  };

  // 화면 표기용 상태 (기본값: YYYY-MM-DD)
  const [displayValue, setDisplayValue] = useState(getTodayDisplayString());
  const [selectedDate, setSelectedDate] = useState<Date>(new Date());

  const addHyphens = (value: string) => {
    if (value.length === 8) {
      return `${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}`;
    }
    return value;
  };

  // 숫자를 타이핑할 때의 처리 (숫자만 입력 허용)
  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const nextValue = e.target.value.replace(/[^0-9]/g, "");
    setDisplayValue(nextValue); // 입력 중에는 하이픈 없이 숫자만 보여줌

    // 8자가 완성되면 실제 Date 객체와도 동기화
    if (nextValue.length === 8) {
      const year = parseInt(nextValue.substring(0, 4));
      const month = parseInt(nextValue.substring(4, 6)) - 1;
      const day = parseInt(nextValue.substring(6, 8));
      const parsedDate = new Date(year, month, day);
      
      if (!isNaN(parsedDate.getTime())) {
        setSelectedDate(parsedDate);
      }
    }
  };

  // 인풋을 클릭했을 때 (포커스 진입): 하이픈을 제거하여 수정하기 편하게 만듦
  const handleFocus = () => {
    const rawValue = displayValue.replace(/[^0-9]/g, "");
    setDisplayValue(rawValue);
  };

  // 다른 곳을 클릭했을 때 (포커스 아웃): 숫자가 8자면 다시 YYYY-MM-DD 형식으로 변환
  const handleBlur = () => {
    const rawValue = displayValue.replace(/[^0-9]/g, "");
    if (rawValue.length === 8) {
      setDisplayValue(addHyphens(rawValue));
    } else {
      // 만약 유저가 입력하다가 말았으면(예: 6글자만 입력) 오늘 날짜로 원상복구
      setDisplayValue(getTodayDisplayString());
      setSelectedDate(new Date());
    }
  };

  // 달력 팝업 등에서 날짜를 바꿨을 때의 처리
  const handleCalendarChange = (value: any) => {
    if (value instanceof Date) {
      setSelectedDate(value);
      const year = value.getFullYear();
      const month = String(value.getMonth() + 1).padStart(2, "0");
      const day = String(value.getDate()).padStart(2, "0");
      setDisplayValue(`${year}-${month}-${day}`);
    }
  };

  return (
    <div className="relative w-fit">
      <input
        type="text"
        maxLength={10}
        value={displayValue}
        onChange={handleInputChange}
        onFocus={handleFocus}
        onBlur={handleBlur}
        placeholder="YYYYMMDD"
        className="w-[100px] h-[30px] bg-card-bg border border-gray-600 rounded text-center text-xs font-bold text-white tracking-wider shadow-inner focus:outline-none focus:border-blue-500 transition-colors"
      />

      {/* 숨김 처리된 달력 */}
      <div className="hidden">
        <Calendar 
          onChange={handleCalendarChange} 
          value={selectedDate} 
        />
      </div>
    </div>
  );
}